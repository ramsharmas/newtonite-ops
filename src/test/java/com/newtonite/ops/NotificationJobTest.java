package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.newtonite.ops.jobs.Job;
import com.newtonite.ops.jobs.JobRepository;
import com.newtonite.ops.jobs.JobStatus;
import com.newtonite.ops.jobs.JobWorker;
import com.newtonite.ops.jobs.NotificationRepository;
import com.newtonite.ops.jobs.Notifier;

import tools.jackson.databind.JsonNode;

class NotificationJobTest extends ApiTestSupport {

	// The real Notifier, wrapped so a test can make it fail like a mail server that's down.
	@MockitoSpyBean
	Notifier notifier;

	@Autowired
	JobWorker worker;

	@Autowired
	JobRepository jobs;

	@Autowired
	NotificationRepository notifications;

	@BeforeEach
	void finishJobsFromEarlierTests() {
		worker.runOnce(Instant.now().plus(Duration.ofDays(1)));
	}

	@Test
	void aChangeNotifiesTheOtherPeopleInvolvedButNotTheOneWhoMadeIt() {
		JsonNode item = createItem("Chen", "Payments", "Notify me");
		long id = item.path("id").asLong();
		ok(post("Ben", "/api/items/" + id + "/claim", null));

		worker.runOnce(Instant.now());

		assertThat(ok(get("Chen", "/api/notifications")).path("notifications").toString())
				.contains("Ben Okafor took \\\"" + item.path("title").asString());
		assertThat(ok(get("Ben", "/api/notifications")).path("notifications").toString())
				.doesNotContain(item.path("title").asString());
		// Leads hear about new items in their team.
		assertThat(ok(get("Asha", "/api/notifications")).path("notifications").toString())
				.contains("Chen Wei created");
	}

	@Test
	void runningTheSameJobTwiceStillSendsOneNotificationPerPerson() {
		createItem("Chen", "Payments", "Delivered once");
		Job job = latestJob();

		notifier.deliver(job);
		long afterFirst = notifications.countByJobId(job.getId());
		notifier.deliver(job);

		assertThat(afterFirst).isEqualTo(2); // the two Payments leads, Asha and Farah
		assertThat(notifications.countByJobId(job.getId())).isEqualTo(afterFirst);
	}

	@Test
	void failedJobIsRetriedLaterAndThenSucceeds() {
		doThrow(new IllegalStateException("mail server down"))
				.doThrow(new IllegalStateException("mail server down"))
				.doCallRealMethod()
				.when(notifier).deliver(any());
		createItem("Chen", "Payments", "Flaky delivery");
		Job job = latestJob();
		Instant now = Instant.now();

		assertThat(worker.runOnce(now)).isZero();
		Job afterFailure = jobs.findById(job.getId()).orElseThrow();
		assertThat(afterFailure.getStatus()).isEqualTo(JobStatus.PENDING);
		assertThat(afterFailure.getAttempts()).isEqualTo(1);
		assertThat(afterFailure.getLastError()).isEqualTo("mail server down");
		// Not due again straight away: it waits before retrying.
		assertThat(worker.runOnce(now)).isZero();

		worker.runOnce(now.plus(Duration.ofMinutes(1)));
		assertThat(worker.runOnce(now.plus(Duration.ofMinutes(2)))).isEqualTo(1);

		Job finished = jobs.findById(job.getId()).orElseThrow();
		assertThat(finished.getStatus()).isEqualTo(JobStatus.DONE);
		assertThat(finished.getAttempts()).isEqualTo(3);
		assertThat(notifications.countByJobId(job.getId())).isEqualTo(2);
	}

	@Test
	void jobIsMarkedDeadAfterFiveFailuresAndKeptForInspection() {
		doThrow(new IllegalStateException("mail server down")).when(notifier).deliver(any());
		createItem("Chen", "Payments", "Never delivered");
		Job job = latestJob();
		Instant now = Instant.now();

		for (int attempt = 1; attempt <= 6; attempt++) {
			worker.runOnce(now.plus(Duration.ofHours(attempt)));
		}

		Job dead = jobs.findById(job.getId()).orElseThrow();
		assertThat(dead.getStatus()).isEqualTo(JobStatus.DEAD);
		assertThat(dead.getAttempts()).isEqualTo(5);
		assertThat(dead.getLastError()).isEqualTo("mail server down");
		assertThat(notifications.countByJobId(job.getId())).isZero();

		doCallRealMethod().when(notifier).deliver(any());
		assertThat(worker.runOnce(now.plus(Duration.ofDays(1)))).isZero();
	}

	private Job latestJob() {
		return jobs.findAll(Sort.by(Sort.Direction.DESC, "id")).getFirst();
	}
}
