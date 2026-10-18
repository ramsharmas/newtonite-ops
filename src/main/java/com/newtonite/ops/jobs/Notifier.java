package com.newtonite.ops.jobs;

import java.time.Instant;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Delivers a notify job. Today that means an in-app notification; email or Slack would
 * go here too. Safe to run more than once for the same job.
 */
@Component
public class Notifier {

	private final NotificationRepository notifications;
	private final ObjectMapper json;

	public Notifier(NotificationRepository notifications, ObjectMapper json) {
		this.notifications = notifications;
		this.json = json;
	}

	public void deliver(Job job) {
		Jobs.NotifyPayload payload = json.readValue(job.getPayload(), Jobs.NotifyPayload.class);
		for (Long userId : payload.recipients()) {
			// A retry, or a second worker, must not notify the same person twice.
			// The unique constraint on (job_id, user_id) backs this up.
			if (!notifications.existsByJobIdAndUserId(job.getId(), userId)) {
				notifications.save(new Notification(userId, payload.itemId(), job.getId(), payload.message(), Instant.now()));
			}
		}
	}
}
