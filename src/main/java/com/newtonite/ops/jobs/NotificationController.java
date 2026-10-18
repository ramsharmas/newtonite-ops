package com.newtonite.ops.jobs;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.newtonite.ops.auth.CurrentUser;
import com.newtonite.ops.auth.User;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	public record NotificationView(Long id, Long itemId, String message, boolean read, Instant createdAt) {
	}

	public record Inbox(long unread, List<NotificationView> notifications) {
	}

	private final NotificationRepository notifications;

	public NotificationController(NotificationRepository notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	public Inbox inbox(@CurrentUser User me) {
		List<NotificationView> latest = notifications.findTop20ByUserIdOrderByIdDesc(me.getId()).stream()
				.map(n -> new NotificationView(n.getId(), n.getItemId(), n.getMessage(), n.getReadAt() != null, n.getCreatedAt()))
				.toList();
		return new Inbox(notifications.countByUserIdAndReadAtIsNull(me.getId()), latest);
	}

	@PostMapping("/read")
	public Inbox markAllRead(@CurrentUser User me) {
		notifications.markAllRead(me.getId(), Instant.now());
		return inbox(me);
	}
}
