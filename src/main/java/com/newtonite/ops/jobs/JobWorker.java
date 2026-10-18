package com.newtonite.ops.jobs;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs queued jobs. Each job gets its own transaction, so one bad job can't block the rest. */
@Component
public class JobWorker {

	private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
	private static final int BATCH_SIZE = 20;

	private final JobRepository jobs;
	private final Notifier notifier;
	private final TransactionTemplate tx;
	private final int maxAttempts;

	public JobWorker(JobRepository jobs, Notifier notifier, PlatformTransactionManager transactionManager,
			@Value("${newtonite.jobs.max-attempts:5}") int maxAttempts) {
		this.jobs = jobs;
		this.notifier = notifier;
		this.tx = new TransactionTemplate(transactionManager);
		this.maxAttempts = maxAttempts;
	}

	/** Runs every job that is due at {@code now}. Returns how many succeeded. */
	public int runOnce(Instant now) {
		List<Long> due = jobs.findDue(now, PageRequest.of(0, BATCH_SIZE));
		int succeeded = 0;
		for (Long id : due) {
			if (run(id, now)) {
				succeeded++;
			}
		}
		return succeeded;
	}

	private boolean run(long id, Instant now) {
		try {
			return Boolean.TRUE.equals(tx.execute(status -> {
				Job job = jobs.findById(id).orElse(null);
				if (job == null || job.getStatus() != JobStatus.PENDING) {
					return false;
				}
				switch (job.getType()) {
					case Jobs.NOTIFY -> notifier.deliver(job);
					default -> throw new IllegalStateException("Unknown job type: " + job.getType());
				}
				job.markDone(now);
				return true;
			}));
		}
		catch (RuntimeException e) {
			// The work above was rolled back. Record the failure in a fresh transaction.
			log.warn("Job {} failed: {}", id, e.getMessage());
			tx.executeWithoutResult(status -> jobs.findById(id)
					.ifPresent(job -> job.recordFailure(e.getMessage(), maxAttempts, now)));
			return false;
		}
	}
}
