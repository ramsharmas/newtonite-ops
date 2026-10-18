package com.newtonite.ops.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "memberships", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "team_id" }))
public class Membership {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Role role;

	protected Membership() {
	}

	public Membership(Long userId, Long teamId, Role role) {
		this.userId = userId;
		this.teamId = teamId;
		this.role = role;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getTeamId() {
		return teamId;
	}

	public Role getRole() {
		return role;
	}
}
