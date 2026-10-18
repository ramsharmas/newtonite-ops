package com.newtonite.ops.items;

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
 * The one record type in the app. Everything a team works on (a refund, an incident,
 * a document check) is a work item; comments and history hang off it.
 */
@Entity
@Table(name = "work_items", indexes = {
		@Index(name = "idx_items_team_status", columnList = "team_id, status"),
		@Index(name = "idx_items_assignee", columnList = "assignee_id") })
public class WorkItem {

	public static final String KIND_TASK = "task";
	public static final String KIND_INCIDENT = "incident";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(length = 4000)
	private String description;

	@Column(nullable = false, length = 20)
	private String kind;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private Status status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Priority priority;

	// Kept next to priority so lists can sort by importance in SQL.
	@Column(nullable = false)
	private int priorityRank;

	@Column(name = "assignee_id")
	private Long assigneeId;

	@Column(nullable = false)
	private Long createdBy;

	private boolean requiresApproval;

	private Long approvedBy;

	// Goes up by one on every change. A save that was based on an older number is refused.
	@Version
	private long version;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	protected WorkItem() {
	}

	public WorkItem(Long teamId, String title, String description, String kind, Priority priority,
			boolean requiresApproval, Long createdBy, Instant now) {
		this.teamId = teamId;
		this.title = title;
		this.description = description;
		this.kind = kind;
		this.status = Status.OPEN;
		setPriority(priority);
		this.requiresApproval = requiresApproval;
		this.createdBy = createdBy;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void touch(Instant now) {
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Long getTeamId() {
		return teamId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getKind() {
		return kind;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public Priority getPriority() {
		return priority;
	}

	public void setPriority(Priority priority) {
		this.priority = priority;
		this.priorityRank = priority.rank();
	}

	public Long getAssigneeId() {
		return assigneeId;
	}

	public void setAssigneeId(Long assigneeId) {
		this.assigneeId = assigneeId;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public boolean isRequiresApproval() {
		return requiresApproval;
	}

	public Long getApprovedBy() {
		return approvedBy;
	}

	public void setApprovedBy(Long approvedBy) {
		this.approvedBy = approvedBy;
	}

	public long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
