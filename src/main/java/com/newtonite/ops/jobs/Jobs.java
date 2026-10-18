package com.newtonite.ops.jobs;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/** Queues jobs. Must be called inside the transaction that makes the change. */
@Service
public class Jobs {

	public static final String NOTIFY = "notify";

	/** What a notify job carries. Recipients are worked out when the change happens. */
	public record NotifyPayload(long itemId, long eventId, List<Long> recipients, String message) {
	}

	private final JobRepository jobs;
	private final ObjectMapper json;

	public Jobs(JobRepository jobs, ObjectMapper json) {
		this.jobs = jobs;
		this.json = json;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void notify(long itemId, long eventId, Collection<Long> recipients, String message) {
		if (recipients.isEmpty()) {
			return;
		}
		NotifyPayload payload = new NotifyPayload(itemId, eventId, List.copyOf(recipients), message);
		jobs.save(new Job(NOTIFY, json.writeValueAsString(payload), Instant.now()));
	}
}
