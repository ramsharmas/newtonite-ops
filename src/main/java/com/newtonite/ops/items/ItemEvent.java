package com.newtonite.ops.items;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One line of an item's history. Written in the same transaction as the change it
 * describes, so there is never a change without its history row (or the other way round).
 */
@Entity
@Table(name = "item_events", indexes = @Index(name = "idx_events_item_id", columnList = "item_id, id"))
public class ItemEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(nullable = false)
	private Long actorId;

	// created, updated, claimed, released, approved, resolved, closed, reopened, cancelled, commented
	@Column(nullable = false, length = 30)
	private String type;

	// JSON, for example {"changes":{"priority":{"from":"high","to":"low"}}}
	@Column(length = 4000)
	private String detail;

	@Column(nullable = false)
	private Instant createdAt;

	protected ItemEvent() {
	}

	public ItemEvent(Long itemId, Long actorId, String type, String detail, Instant createdAt) {
		this.itemId = itemId;
		this.actorId = actorId;
		this.type = type;
		this.detail = detail;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getItemId() {
		return itemId;
	}

	public Long getActorId() {
		return actorId;
	}

	public String getType() {
		return type;
	}

	public String getDetail() {
		return detail;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
