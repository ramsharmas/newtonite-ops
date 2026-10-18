package com.newtonite.ops.items;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "comments", indexes = @Index(name = "idx_comments_item", columnList = "item_id"))
public class Comment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(nullable = false)
	private Long authorId;

	@Column(nullable = false, length = 2000)
	private String body;

	@Column(nullable = false)
	private Instant createdAt;

	protected Comment() {
	}

	public Comment(Long itemId, Long authorId, String body, Instant createdAt) {
		this.itemId = itemId;
		this.authorId = authorId;
		this.body = body;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getItemId() {
		return itemId;
	}

	public Long getAuthorId() {
		return authorId;
	}

	public String getBody() {
		return body;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
