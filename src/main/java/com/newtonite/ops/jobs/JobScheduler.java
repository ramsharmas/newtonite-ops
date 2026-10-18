package com.newtonite.ops.jobs;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for due jobs. Turned off in tests, which call {@link JobWorker#runOnce} themselves. */
@Component
@ConditionalOnProperty(name = "newtonite.jobs.enabled", havingValue = "true", matchIfMissing = true)
class JobScheduler {

	private final JobWorker worker;

	JobScheduler(JobWorker worker) {
		this.worker = worker;
	}

	@Scheduled(fixedDelayString = "${newtonite.jobs.poll-ms:1000}")
	void poll() {
		worker.runOnce(Instant.now());
	}
}
