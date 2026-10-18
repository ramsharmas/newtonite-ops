package com.newtonite.ops.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A signed-in browser. The token is sent as "Authorization: Bearer ..." on every API call. */
@Entity
@Table(name = "login_tokens")
public class LoginToken {

	@Id
	@Column(length = 64)
	private String token;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private Instant createdAt;

	protected LoginToken() {
	}

	public LoginToken(String token, Long userId, Instant createdAt) {
		this.token = token;
		this.userId = userId;
		this.createdAt = createdAt;
	}

	public String getToken() {
		return token;
	}

	public Long getUserId() {
		return userId;
	}
}
