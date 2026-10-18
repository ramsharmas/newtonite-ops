package com.newtonite.ops.items;

import static com.newtonite.ops.items.Status.CLOSED;
import static com.newtonite.ops.items.Status.IN_PROGRESS;
import static com.newtonite.ops.items.Status.OPEN;
import static com.newtonite.ops.items.Status.RESOLVED;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.newtonite.ops.auth.Role;
import com.newtonite.ops.web.ApiException;

/**
 * Every status change goes through this table: which statuses an action is allowed from,
 * and who may do it. The API uses it to refuse actions, and the item page uses it to
 * grey out buttons with the reason, so both always agree.
 */
public final class Workflow {

	public enum Action {
		CLAIM("claimed", Who.ANY_MEMBER, OPEN),
		RELEASE("released", Who.OWNER_OR_LEAD, IN_PROGRESS),
		APPROVE("approved", Who.LEAD, OPEN, IN_PROGRESS),
		RESOLVE("resolved", Who.OWNER_OR_LEAD, IN_PROGRESS),
		CLOSE("closed", Who.LEAD, RESOLVED),
		REOPEN("reopened", Who.ANY_MEMBER, RESOLVED, CLOSED),
		CANCEL("cancelled", Who.LEAD, OPEN, IN_PROGRESS);

		private final String eventType;
		private final Who who;
		private final Set<Status> from;

		Action(String eventType, Who who, Status... from) {
			this.eventType = eventType;
			this.who = who;
			this.from = EnumSet.copyOf(Arrays.asList(from));
		}

		public String key() {
			return name().toLowerCase();
		}

		/** The history entry this action writes, for example "claimed". */
		public String eventType() {
			return eventType;
		}

		public static Action parse(String value) {
			for (Action action : values()) {
				if (action.key().equalsIgnoreCase(value)) {
					return action;
				}
			}
			throw ApiException.notFound("Action " + value);
		}
	}

	private enum Who {
		ANY_MEMBER, OWNER_OR_LEAD, LEAD
	}

	/** Why an action can't be done right now. */
	public record Denial(HttpStatus status, String code, String message) {

		public ApiException toException() {
			return new ApiException(status, code, message);
		}
	}

	private Workflow() {
	}

	/** Returns null if {@code userId} (with {@code role} in the item's team) may do {@code action} now. */
	public static Denial check(Action action, WorkItem item, long userId, Role role) {
		boolean lead = role == Role.LEAD;
		boolean owner = Objects.equals(item.getAssigneeId(), userId);

		if (action == Action.CLAIM && item.getAssigneeId() != null) {
			return new Denial(HttpStatus.CONFLICT, "already_claimed", "Someone already owns this item");
		}
		if (action == Action.APPROVE && !item.isRequiresApproval()) {
			return new Denial(HttpStatus.CONFLICT, "no_approval_needed", "This item doesn't need approval");
		}
		if (action == Action.APPROVE && item.getApprovedBy() != null) {
			return new Denial(HttpStatus.CONFLICT, "already_approved", "Already approved");
		}
		if (!action.from.contains(item.getStatus())) {
			return new Denial(HttpStatus.CONFLICT, "invalid_transition",
					"Can't " + action.key() + " an item that is " + item.getStatus().label());
		}
		switch (action.who) {
			case LEAD -> {
				if (!lead) {
					return new Denial(HttpStatus.FORBIDDEN, "forbidden", "Only a team lead can " + action.key() + " items");
				}
			}
			case OWNER_OR_LEAD -> {
				if (!owner && !lead) {
					return new Denial(HttpStatus.FORBIDDEN, "forbidden",
							"Only the owner or a team lead can " + action.key() + " this item");
				}
			}
			case ANY_MEMBER -> {
			}
		}

		return switch (action) {
			// Four-eyes rule: the person who raised or is doing the work can't sign it off.
			case APPROVE -> owner || Objects.equals(item.getCreatedBy(), userId)
					? new Denial(HttpStatus.FORBIDDEN, "own_work", "You can't approve your own work")
					: null;
			case RESOLVE -> item.isRequiresApproval() && item.getApprovedBy() == null
					? new Denial(HttpStatus.CONFLICT, "needs_approval", "A team lead has to approve this before it can be resolved")
					: null;
			default -> null;
		};
	}

	/** Changes the item. Call {@link #check} first. */
	public static void apply(Action action, WorkItem item, long userId) {
		switch (action) {
			// Claiming has to be a single conditional UPDATE to be safe when two people click at once.
			case CLAIM -> throw new IllegalStateException("Claim with WorkItemRepository.claim");
			case RELEASE -> {
				item.setAssigneeId(null);
				item.setStatus(OPEN);
			}
			case APPROVE -> item.setApprovedBy(userId);
			case RESOLVE -> item.setStatus(RESOLVED);
			case CLOSE -> item.setStatus(CLOSED);
			case REOPEN -> item.setStatus(item.getAssigneeId() == null ? OPEN : IN_PROGRESS);
			case CANCEL -> item.setStatus(Status.CANCELLED);
		}
	}
}
