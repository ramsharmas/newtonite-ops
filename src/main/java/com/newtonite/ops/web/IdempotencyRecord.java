package com.newtonite.ops.web;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** The saved response for one Idempotency-Key, so a repeat of the request gets the same answer. */
@Entity
@Table(name = "idempotency_keys",
		uniqueConstraints = @UniqueConstraint(name = "uq_idempotency_key", columnNames = { "user_id", "idem_key" }))
public class IdempotencyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "idem_key", nullable = false, length = 100)
	private String idemKey;

	@Column(nullable = false, length = 200)
	private String route;

	@Column(nullable = false)
	private int status;

	@Column(nullable = false, length = 100_000)
	private String body;

	@Column(nullable = false)
	private Instant createdAt;

	protected IdempotencyRecord() {
	}

	public IdempotencyRecord(Long userId, String idemKey, String route, int status, String body, Instant createdAt) {
		this.userId = userId;
		this.idemKey = idemKey;
		this.route = route;
		this.status = status;
		this.body = body;
		this.createdAt = createdAt;
	}

	public String getRoute() {
		return route;
	}

	public int getStatus() {
		return status;
	}

	public String getBody() {
		return body;
	}
}
