package com.newtonite.ops.jobs;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Work to do after a change has been saved, such as sending notifications. It is written
 * in the same transaction as the change (the "outbox" pattern), so a job exists exactly
 * when its change was committed, and the worker picks it up from here.
 */
@Entity
@Table(name = "jobs", indexes = @Index(name = "idx_jobs_due", columnList = "status, run_after"))
public class Job {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 30)
	private String type;

	@Column(nullable = false, length = 4000)
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private JobStatus status;

	private int attempts;

	@Column(name = "run_after", nullable = false)
	private Instant runAfter;

	@Column(length = 1000)
	private String lastError;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	// Stops two workers from both finishing the same job.
	@Version
	private long version;

	protected Job() {
	}

	public Job(String type, String payload, Instant now) {
		this.type = type;
		this.payload = payload;
		this.status = JobStatus.PENDING;
		this.runAfter = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void markDone(Instant now) {
		attempts++;
		status = JobStatus.DONE;
		lastError = null;
		updatedAt = now;
	}

	/** Schedules a retry with a growing wait (2s, 4s, 8s, ...), or gives up after {@code maxAttempts}. */
	public void recordFailure(String error, int maxAttempts, Instant now) {
		if (status != JobStatus.PENDING) {
			return;
		}
		attempts++;
		lastError = error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 1000));
		if (attempts >= maxAttempts) {
			status = JobStatus.DEAD;
		}
		else {
			runAfter = now.plusSeconds(1L << attempts);
		}
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getType() {
		return type;
	}

	public String getPayload() {
		return payload;
	}

	public JobStatus getStatus() {
		return status;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getRunAfter() {
		return runAfter;
	}

	public String getLastError() {
		return lastError;
	}
}
