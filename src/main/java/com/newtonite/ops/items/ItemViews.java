package com.newtonite.ops.items;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.newtonite.ops.auth.Role;
import com.newtonite.ops.auth.Team;
import com.newtonite.ops.auth.TeamRepository;
import com.newtonite.ops.auth.User;
import com.newtonite.ops.auth.UserRepository;

import tools.jackson.databind.ObjectMapper;

/** What the API returns for items. Entities never leave the service layer. */
@Component
public class ItemViews {

	public record UserRef(Long id, String name) {
	}

	public record TeamRef(Long id, String name) {
	}

	public record ItemView(Long id, TeamRef team, String title, String description, String kind, String status,
			String priority, boolean requiresApproval, UserRef approvedBy, UserRef assignee, UserRef createdBy,
			long version, Instant createdAt, Instant updatedAt) {
	}

	/**
	 * One button on the item page: can I do this, and if not, why not. The page hides
	 * actions that make no sense for the item right now (codes like "invalid_transition")
	 * and greys out the rest with the reason.
	 */
	public record ActionView(String name, boolean allowed, String code, String reason) {
	}

	public record CommentView(Long id, UserRef author, String body, Instant createdAt) {
	}

	public record EventView(Long id, String type, UserRef actor, Map<?, ?> detail, Instant createdAt) {
	}

	public record ItemDetail(ItemView item, String myRole, List<ActionView> actions, List<CommentView> comments,
			List<EventView> history) {
	}

	private final UserRepository users;
	private final TeamRepository teams;
	private final ObjectMapper json;

	public ItemViews(UserRepository users, TeamRepository teams, ObjectMapper json) {
		this.users = users;
		this.teams = teams;
		this.json = json;
	}

	public ItemView item(WorkItem item) {
		return items(List.of(item)).getFirst();
	}

	public List<ItemView> items(List<WorkItem> items) {
		Names names = names(
				items.stream().flatMap(w -> Stream.of(w.getAssigneeId(), w.getCreatedBy(), w.getApprovedBy())).toList(),
				items.stream().map(WorkItem::getTeamId).toList());
		return items.stream().map(w -> item(w, names)).toList();
	}

	public ItemDetail detail(WorkItem item, Role myRole, List<ActionView> actions, List<Comment> comments,
			List<ItemEvent> events) {
		Names names = names(
				Stream.of(Stream.of(item.getAssigneeId(), item.getCreatedBy(), item.getApprovedBy()),
						comments.stream().map(Comment::getAuthorId),
						events.stream().map(ItemEvent::getActorId))
						.flatMap(s -> s).toList(),
				List.of(item.getTeamId()));
		return new ItemDetail(
				item(item, names),
				myRole.key(),
				actions,
				comments.stream().map(c -> new CommentView(c.getId(), names.user(c.getAuthorId()), c.getBody(), c.getCreatedAt())).toList(),
				events.stream().map(e -> new EventView(e.getId(), e.getType(), names.user(e.getActorId()), detail(e), e.getCreatedAt())).toList());
	}

	public CommentView comment(Comment comment) {
		return new CommentView(comment.getId(), user(comment.getAuthorId()), comment.getBody(), comment.getCreatedAt());
	}

	public UserRef user(Long id) {
		return id == null ? null : names(List.of(id), List.of()).user(id);
	}

	private ItemView item(WorkItem w, Names names) {
		return new ItemView(w.getId(), names.team(w.getTeamId()), w.getTitle(), w.getDescription(), w.getKind(),
				w.getStatus().key(), w.getPriority().key(), w.isRequiresApproval(), names.user(w.getApprovedBy()),
				names.user(w.getAssigneeId()), names.user(w.getCreatedBy()), w.getVersion(), w.getCreatedAt(),
				w.getUpdatedAt());
	}

	private Map<?, ?> detail(ItemEvent event) {
		return event.getDetail() == null ? Map.of() : json.readValue(event.getDetail(), Map.class);
	}

	private Names names(Collection<Long> userIds, Collection<Long> teamIds) {
		Set<Long> wantedUsers = userIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
		Set<Long> wantedTeams = teamIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
		return new Names(
				users.findAllById(wantedUsers).stream().collect(Collectors.toMap(User::getId, User::getName)),
				teams.findAllById(wantedTeams).stream().collect(Collectors.toMap(Team::getId, Team::getName)));
	}

	private record Names(Map<Long, String> users, Map<Long, String> teams) {

		UserRef user(Long id) {
			return id == null ? null : new UserRef(id, users.getOrDefault(id, "Unknown"));
		}

		TeamRef team(Long id) {
			return new TeamRef(id, teams.getOrDefault(id, "Unknown"));
		}
	}
}
