package com.newtonite.ops.items;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newtonite.ops.auth.Access;
import com.newtonite.ops.auth.Role;
import com.newtonite.ops.auth.User;
import com.newtonite.ops.items.ItemViews.ActionView;
import com.newtonite.ops.items.ItemViews.CommentView;
import com.newtonite.ops.items.ItemViews.ItemDetail;
import com.newtonite.ops.items.ItemViews.HistoryPage;
import com.newtonite.ops.items.ItemViews.ItemView;
import com.newtonite.ops.items.ItemViews.UserRef;
import com.newtonite.ops.items.Workflow.Action;
import com.newtonite.ops.items.Workflow.Denial;
import com.newtonite.ops.jobs.Jobs;
import com.newtonite.ops.web.ApiException;

import tools.jackson.databind.ObjectMapper;

/**
 * Everything that reads or changes work items. Each public method is one transaction:
 * the change, its history row and its notification job are saved together or not at all.
 */
@Service
@Transactional
public class ItemService {

	public record NewItem(@NotNull Long teamId, @NotBlank @Size(max = 200) String title,
			@Size(max = 4000) String description, String priority, String kind, Boolean requiresApproval) {
	}

	/** Only the fields that are set are changed. {@code version} is the one the editor started from. */
	public record ItemEdit(@NotNull Long version, @Size(max = 200) String title, @Size(max = 4000) String description,
			String priority) {
	}

	/** One page of the item list. {@code nextCursor} is null on the last page. */
	public record ItemPage(List<ItemView> items, long total, String nextCursor) {
	}

	/** The numbers on the overview cards. Each one equals the {@code total} of the list it opens. */
	public record Overview(long active, long mine, long unassigned, long urgent, long needsApproval, long stale,
			long staleAfterHours) {
	}

	private static final int DEFAULT_PAGE_SIZE = 30;
	private static final int MAX_PAGE_SIZE = 100;
	private static final int HISTORY_PAGE_SIZE = 50;

	private final WorkItemRepository items;
	private final CommentRepository comments;
	private final ItemEventRepository events;
	private final ItemSearch search;
	private final Access access;
	private final Jobs jobs;
	private final ItemViews views;
	private final ObjectMapper json;

	public ItemService(WorkItemRepository items, CommentRepository comments, ItemEventRepository events,
			ItemSearch search, Access access, Jobs jobs, ItemViews views, ObjectMapper json) {
		this.items = items;
		this.comments = comments;
		this.events = events;
		this.search = search;
		this.access = access;
		this.jobs = jobs;
		this.views = views;
		this.json = json;
	}

	@Transactional(readOnly = true)
	public ItemPage list(User me, ItemSearch.Filter filter, String cursor, Integer limit) {
		int size = limit == null ? DEFAULT_PAGE_SIZE : Math.clamp(limit, 1, MAX_PAGE_SIZE);
		ItemSearch.Page page = search.find(me.getId(), access.teamIds(me.getId()), filter, cursor, size);
		return new ItemPage(views.items(page.items()), page.total(), page.nextCursor());
	}

	@Transactional(readOnly = true)
	public Overview overview(User me, Long teamId) {
		ItemSearch.Counts counts = search.counts(me.getId(), access.teamIds(me.getId()), teamId);
		return new Overview(counts.active(), counts.mine(), counts.unassigned(), counts.urgent(),
				counts.needsApproval(), counts.stale(), search.staleAfter().toHours());
	}

	@Transactional(readOnly = true)
	public ItemDetail detail(User me, long id) {
		Visible visible = visible(me, id);
		List<ActionView> actions = Arrays.stream(Action.values())
				.map(action -> {
					Denial denial = Workflow.check(action, visible.item(), me.getId(), visible.role());
					return denial == null
							? new ActionView(action.key(), true, null, null)
							: new ActionView(action.key(), false, denial.code(), denial.message());
				})
				.toList();
		Activity latest = activity(id, null, HISTORY_PAGE_SIZE);
		return views.detail(visible.item(), visible.role(), actions, latest.comments(), latest.events(), latest.hasOlder(),
				comments.countByItemId(id), events.countByItemId(id));
	}

	/** Older activity, for the "show older" button. {@code before} is the id of the oldest event the caller already has. */
	@Transactional(readOnly = true)
	public HistoryPage history(User me, long id, Long before, Integer limit) {
		visible(me, id);
		int size = limit == null ? HISTORY_PAGE_SIZE : Math.clamp(limit, 1, MAX_PAGE_SIZE);
		Activity page = activity(id, before, size);
		return views.history(page.comments(), page.events(), page.hasOlder());
	}

	/** A window of an item's history (oldest first) plus the comments that appear in it. */
	private record Activity(List<Comment> comments, List<ItemEvent> events, boolean hasOlder) {
	}

	private Activity activity(long itemId, Long before, int size) {
		PageRequest page = PageRequest.of(0, size + 1);
		List<ItemEvent> rows = before == null
				? events.findByItemIdOrderByIdDesc(itemId, page)
				: events.findByItemIdAndIdLessThanOrderByIdDesc(itemId, before, page);
		boolean hasOlder = rows.size() > size;
		List<ItemEvent> window = new ArrayList<>(hasOlder ? rows.subList(0, size) : rows);
		Collections.reverse(window);

		// A "commented" event points at its comment; load just those bodies.
		List<Long> commentIds = window.stream()
				.filter(event -> "commented".equals(event.getType()) && event.getDetail() != null)
				.map(event -> json.readTree(event.getDetail()).path("commentId").asLong(0))
				.filter(commentId -> commentId > 0)
				.toList();
		List<Comment> found = new ArrayList<>(commentIds.isEmpty() ? List.of() : comments.findAllById(commentIds));
		found.sort(Comparator.comparing(Comment::getId));
		return new Activity(found, window, hasOlder);
	}

	public ItemView create(User me, NewItem request) {
		if (access.roleIn(me.getId(), request.teamId()).isEmpty()) {
			throw ApiException.notFound("Team");
		}
		String kind = request.kind() == null ? WorkItem.KIND_TASK : request.kind();
		if (!kind.equals(WorkItem.KIND_TASK) && !kind.equals(WorkItem.KIND_INCIDENT)) {
			throw ApiException.badRequest("kind must be 'task' or 'incident'");
		}
		Priority priority = request.priority() == null ? Priority.MEDIUM : Priority.parse(request.priority());

		Instant now = Instant.now();
		WorkItem item = items.save(new WorkItem(request.teamId(), request.title().trim(), blankToNull(request.description()),
				kind, priority, Boolean.TRUE.equals(request.requiresApproval()), me.getId(), now));
		// Team leads hear about new work in their team.
		record(item, me, "created", Map.of(), Set.copyOf(access.leadsOf(item.getTeamId())), now);
		return views.item(item);
	}

	public ItemView update(User me, long id, ItemEdit edit) {
		WorkItem item = visible(me, id).item();
		if (item.getVersion() != edit.version()) {
			throw conflict(item);
		}
		if (item.getStatus().isFinished()) {
			throw new ApiException(HttpStatus.CONFLICT, "finished",
					"This item is " + item.getStatus().label() + " and can't be edited");
		}

		Map<String, Object> changes = new LinkedHashMap<>();
		if (edit.title() != null) {
			String title = edit.title().trim();
			if (title.isEmpty()) {
				throw ApiException.badRequest("title can't be empty");
			}
			if (!title.equals(item.getTitle())) {
				changes.put("title", change(item.getTitle(), title));
				item.setTitle(title);
			}
		}
		if (edit.description() != null) {
			String description = blankToNull(edit.description());
			if (!Objects.equals(description, item.getDescription())) {
				changes.put("description", change(item.getDescription(), description));
				item.setDescription(description);
			}
		}
		if (edit.priority() != null) {
			Priority priority = Priority.parse(edit.priority());
			if (priority != item.getPriority()) {
				changes.put("priority", change(item.getPriority().key(), priority.key()));
				item.setPriority(priority);
			}
		}
		if (changes.isEmpty()) {
			return views.item(item);
		}

		Instant now = Instant.now();
		item.touch(now);
		// Hibernate adds "where version = ?" to this UPDATE. If someone else saved after we
		// read the item, it matches no row and throws; the controller turns that into a 409.
		items.saveAndFlush(item);
		record(item, me, "updated", Map.of("changes", changes), Set.of(), now);
		return views.item(item);
	}

	public ItemView claim(User me, long id) {
		Visible visible = visible(me, id);
		Denial denial = Workflow.check(Action.CLAIM, visible.item(), me.getId(), visible.role());
		if (denial != null) {
			throw "already_claimed".equals(denial.code()) ? alreadyClaimed(me, visible.item()) : denial.toException();
		}

		Instant now = Instant.now();
		if (items.claim(id, me.getId(), now) == 0) {
			// Someone else claimed it between our read above and this write.
			throw alreadyClaimed(me, items.findById(id).orElseThrow());
		}
		WorkItem item = items.findById(id).orElseThrow();
		record(item, me, Action.CLAIM.eventType(), Map.of(), Set.of(), now);
		return views.item(item);
	}

	public ItemView act(User me, long id, Action action) {
		if (action == Action.CLAIM) {
			return claim(me, id);
		}
		Visible visible = visible(me, id);
		WorkItem item = visible.item();
		Denial denial = Workflow.check(action, item, me.getId(), visible.role());
		if (denial != null) {
			throw denial.toException();
		}

		Long previousOwner = item.getAssigneeId();
		Workflow.apply(action, item, me.getId());
		Instant now = Instant.now();
		item.touch(now);
		items.saveAndFlush(item);
		// If a lead releases someone's item, that person should hear about it.
		record(item, me, action.eventType(), Map.of(), previousOwner == null ? Set.of() : Set.of(previousOwner), now);
		return views.item(item);
	}

	public CommentView comment(User me, long id, String body) {
		WorkItem item = visible(me, id).item();
		Instant now = Instant.now();
		// Comments don't change the item itself, so they don't bump its version
		// and can't cause edit conflicts. They do count as activity, so a discussed
		// item doesn't look forgotten.
		Comment comment = comments.save(new Comment(id, me.getId(), body.trim(), now));
		items.touch(id, now);
		record(item, me, "commented", Map.of("commentId", comment.getId()), Set.of(), now);
		return views.comment(comment);
	}

	/** Builds the 409 for an edit that lost a race, with the item as it is now. */
	@Transactional(readOnly = true)
	public ApiException conflictWithCurrent(User me, long id) {
		return conflict(visible(me, id).item());
	}

	private record Visible(WorkItem item, Role role) {
	}

	private Visible visible(User me, long id) {
		WorkItem item = items.findById(id).orElseThrow(() -> ApiException.notFound("Item"));
		// People outside the team get the same answer as for an item that doesn't exist.
		Role role = access.roleIn(me.getId(), item.getTeamId()).orElseThrow(() -> ApiException.notFound("Item"));
		return new Visible(item, role);
	}

	/** Writes the history row and queues notifications, in the caller's transaction. */
	private void record(WorkItem item, User actor, String type, Map<String, ?> detail, Set<Long> alsoNotify, Instant now) {
		ItemEvent event = events.save(new ItemEvent(item.getId(), actor.getId(), type, json.writeValueAsString(detail), now));

		Set<Long> recipients = new LinkedHashSet<>(alsoNotify);
		recipients.add(item.getCreatedBy());
		if (item.getAssigneeId() != null) {
			recipients.add(item.getAssigneeId());
		}
		recipients.remove(actor.getId());
		jobs.notify(item.getId(), event.getId(), recipients,
				actor.getName() + " " + describe(type) + " \"" + item.getTitle() + "\"");
	}

	private static String describe(String eventType) {
		return switch (eventType) {
			case "claimed" -> "took";
			case "updated" -> "edited";
			case "commented" -> "commented on";
			default -> eventType;
		};
	}

	private ApiException conflict(WorkItem current) {
		return new ApiException(HttpStatus.CONFLICT, "conflict", "Someone else saved this item while you were editing")
				.with("current", views.item(current));
	}

	private ApiException alreadyClaimed(User me, WorkItem current) {
		if (current.getAssigneeId() == null) {
			return new ApiException(HttpStatus.CONFLICT, "changed", "This item just changed. Reload and try again.");
		}
		if (current.getAssigneeId().equals(me.getId())) {
			return new ApiException(HttpStatus.CONFLICT, "already_claimed", "You already own this item");
		}
		UserRef owner = views.user(current.getAssigneeId());
		return new ApiException(HttpStatus.CONFLICT, "already_claimed", "Already taken by " + owner.name())
				.with("owner", owner);
	}

	private static Map<String, Object> change(Object from, Object to) {
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("from", from);
		change.put("to", to);
		return change;
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}
}
