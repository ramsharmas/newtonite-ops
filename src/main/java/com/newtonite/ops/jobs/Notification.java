package com.newtonite.ops.jobs;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// (job_id, user_id) is unique: even if a job runs twice, each person gets one notification.
@Entity
@Table(name = "notifications",
		uniqueConstraints = @UniqueConstraint(name = "uq_notification_per_job", columnNames = { "job_id", "user_id" }),
		indexes = @Index(name = "idx_notifications_user", columnList = "user_id"))
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false)
	private Long itemId;

	@Column(name = "job_id", nullable = false)
	private Long jobId;

	@Column(nullable = false, length = 300)
	private String message;

	private Instant readAt;

	@Column(nullable = false)
	private Instant createdAt;

	protected Notification() {
	}

	public Notification(Long userId, Long itemId, Long jobId, String message, Instant createdAt) {
		this.userId = userId;
		this.itemId = itemId;
		this.jobId = jobId;
		this.message = message;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getItemId() {
		return itemId;
	}

	public Long getJobId() {
		return jobId;
	}

	public String getMessage() {
		return message;
	}

	public Instant getReadAt() {
		return readAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
