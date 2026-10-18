package com.newtonite.ops.items;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.newtonite.ops.web.ApiException;

/**
 * The item list queries. Built by hand because every filter is optional.
 *
 * <p>Lists are paged with a <em>keyset cursor</em> (the sort values of the last row seen)
 * instead of an offset: the cost of a page doesn't grow with how deep you are, and rows
 * that change while someone is paging can't push others onto the wrong page twice.
 */
@Repository
public class ItemSearch {

	/**
	 * All fields are optional.
	 *
	 * @param q         words to find in the title or description
	 * @param status    a status, or "active" for everything not closed or cancelled (open, in progress, resolved)
	 * @param assigned  "me" or "unassigned"
	 * @param priority  "urgent", "high", "medium" or "low"
	 * @param attention "approval" (active and still waiting for a lead) or "stale" (active, no activity for a while)
	 * @param sort      "priority", "created" or "updated" (the default)
	 */
	public record Filter(String q, Long teamId, String status, String assigned, String priority, String attention,
			String sort) {
	}

	/** One page of results. {@code nextCursor} is null on the last page. */
	public record Page(List<WorkItem> items, long total, String nextCursor) {
	}

	/** The numbers behind the "what needs attention" cards. Each matches a list filter exactly. */
	public record Counts(long active, long mine, long unassigned, long urgent, long needsApproval, long stale) {
	}

	// Predicates are shared by the list filters and the overview counts, so a card's number
	// always equals the length of the list it opens.
	// "Active" is anything not finished. A resolved item still needs a lead to close it, so it stays in view.
	private static final String ACTIVE = "w.status in (com.newtonite.ops.items.Status.OPEN, com.newtonite.ops.items.Status.IN_PROGRESS,"
			+ " com.newtonite.ops.items.Status.RESOLVED)";
	private static final String AWAITING_APPROVAL = "w.requiresApproval = true and w.approvedBy is null";
	private static final String STALE = "w.updatedAt < :staleBefore";

	static final int MAX_QUERY_LENGTH = 200;

	@PersistenceContext
	private EntityManager em;

	private final Duration staleAfter;

	public ItemSearch(@Value("${newtonite.stale-after-hours:24}") long staleAfterHours) {
		this.staleAfter = Duration.ofHours(staleAfterHours);
	}

	public Duration staleAfter() {
		return staleAfter;
	}

	public Page find(long userId, Collection<Long> teamIds, Filter filter, String cursor, int limit) {
		if (teamIds.isEmpty()) {
			return new Page(List.of(), 0, null);
		}
		Scope scope = scope(userId, teamIds, filter);
		String sort = sortOf(filter);

		long total = count(scope);

		StringBuilder jpql = new StringBuilder("select w from WorkItem w where ").append(scope.where);
		Map<String, Object> params = new HashMap<>(scope.params);
		if (StringUtils.hasText(cursor)) {
			jpql.append(" and ").append(after(sort, Cursor.decode(cursor, sort), params));
		}
		jpql.append(switch (sort) {
			case "priority" -> " order by w.priorityRank, w.updatedAt desc, w.id desc";
			case "created" -> " order by w.createdAt desc, w.id desc";
			default -> " order by w.updatedAt desc, w.id desc";
		});

		TypedQuery<WorkItem> query = em.createQuery(jpql.toString(), WorkItem.class);
		params.forEach(query::setParameter);
		// One extra row tells us whether there is another page without a second query.
		List<WorkItem> rows = query.setMaxResults(limit + 1).getResultList();
		if (rows.size() <= limit) {
			return new Page(rows, total, null);
		}
		List<WorkItem> items = List.copyOf(rows.subList(0, limit));
		return new Page(items, total, Cursor.of(sort, items.getLast()).encode());
	}

	/** One query for all the overview numbers. */
	public Counts counts(long userId, Collection<Long> teamIds, Long teamId) {
		if (teamIds.isEmpty()) {
			return new Counts(0, 0, 0, 0, 0, 0);
		}
		String jpql = "select count(w),"
				+ " sum(case when w.assigneeId = :me then 1 else 0 end),"
				+ " sum(case when w.assigneeId is null then 1 else 0 end),"
				+ " sum(case when w.priority = com.newtonite.ops.items.Priority.URGENT then 1 else 0 end),"
				+ " sum(case when " + AWAITING_APPROVAL + " then 1 else 0 end),"
				+ " sum(case when " + STALE + " then 1 else 0 end)"
				+ " from WorkItem w where w.teamId in :teams and " + ACTIVE
				+ (teamId != null ? " and w.teamId = :teamId" : "");
		TypedQuery<Object[]> query = em.createQuery(jpql, Object[].class)
				.setParameter("me", userId)
				.setParameter("teams", teamIds)
				.setParameter("staleBefore", Instant.now().minus(staleAfter));
		if (teamId != null) {
			query.setParameter("teamId", teamId);
		}
		Object[] row = query.getSingleResult();
		return new Counts(number(row[0]), number(row[1]), number(row[2]), number(row[3]), number(row[4]), number(row[5]));
	}

	// ---- Filtering ------------------------------------------------------------------------

	private record Scope(String where, Map<String, Object> params) {
	}

	private Scope scope(long userId, Collection<Long> teamIds, Filter filter) {
		// Only the caller's teams, always. The other filters narrow it further.
		StringBuilder where = new StringBuilder("w.teamId in :teams");
		Map<String, Object> params = new HashMap<>();
		params.put("teams", teamIds);

		if (StringUtils.hasText(filter.q())) {
			String text = filter.q().trim();
			if (text.length() > MAX_QUERY_LENGTH) {
				throw ApiException.badRequest("q is too long (max " + MAX_QUERY_LENGTH + " characters)");
			}
			where.append(" and (lower(w.title) like :q escape '!' or lower(w.description) like :q escape '!')");
			params.put("q", "%" + escapeLike(text.toLowerCase()) + "%");
		}
		if (filter.teamId() != null) {
			where.append(" and w.teamId = :teamId");
			params.put("teamId", filter.teamId());
		}
		if ("active".equals(filter.status())) {
			where.append(" and ").append(ACTIVE);
		}
		else if (StringUtils.hasText(filter.status())) {
			where.append(" and w.status = :status");
			params.put("status", Status.parse(filter.status()));
		}
		if ("me".equals(filter.assigned())) {
			where.append(" and w.assigneeId = :me");
			params.put("me", userId);
		}
		else if ("unassigned".equals(filter.assigned())) {
			where.append(" and w.assigneeId is null");
		}
		else if (StringUtils.hasText(filter.assigned())) {
			throw ApiException.badRequest("assigned must be 'me' or 'unassigned'");
		}
		if (StringUtils.hasText(filter.priority())) {
			where.append(" and w.priority = :priority");
			params.put("priority", Priority.parse(filter.priority()));
		}
		if ("approval".equals(filter.attention())) {
			where.append(" and ").append(ACTIVE).append(" and ").append(AWAITING_APPROVAL);
		}
		else if ("stale".equals(filter.attention())) {
			where.append(" and ").append(ACTIVE).append(" and ").append(STALE);
			params.put("staleBefore", Instant.now().minus(staleAfter));
		}
		else if (StringUtils.hasText(filter.attention())) {
			throw ApiException.badRequest("attention must be 'approval' or 'stale'");
		}
		return new Scope(where.toString(), params);
	}

	private long count(Scope scope) {
		TypedQuery<Long> query = em.createQuery("select count(w) from WorkItem w where " + scope.where, Long.class);
		scope.params.forEach(query::setParameter);
		return query.getSingleResult();
	}

	private static String sortOf(Filter filter) {
		String sort = StringUtils.hasText(filter.sort()) ? filter.sort() : "updated";
		if (!List.of("priority", "created", "updated").contains(sort)) {
			throw ApiException.badRequest("sort must be 'priority', 'created' or 'updated'");
		}
		return sort;
	}

	// ---- Cursor ---------------------------------------------------------------------------

	/** Everything that sorts a row: enough to say "give me the rows after this one". */
	private record Cursor(String sort, int rank, Instant at, long id) {

		static Cursor of(String sort, WorkItem item) {
			Instant at = "created".equals(sort) ? item.getCreatedAt() : item.getUpdatedAt();
			return new Cursor(sort, item.getPriority().rank(), at, item.getId());
		}

		String encode() {
			String raw = sort + "|" + rank + "|" + at + "|" + id;
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		static Cursor decode(String token, String expectedSort) {
			try {
				String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
				if (parts.length != 4 || !parts[0].equals(expectedSort)) {
					throw new IllegalArgumentException("cursor was made for a different sort");
				}
				return new Cursor(parts[0], Integer.parseInt(parts[1]), Instant.parse(parts[2]), Long.parseLong(parts[3]));
			}
			catch (RuntimeException e) {
				throw ApiException.badRequest("Invalid cursor");
			}
		}
	}

	/** The "rows after the cursor" condition for each sort order. Ties are broken by id. */
	private static String after(String sort, Cursor cursor, Map<String, Object> params) {
		params.put("cAt", cursor.at());
		params.put("cId", cursor.id());
		switch (sort) {
			case "priority" -> {
				params.put("cRank", cursor.rank());
				return "(w.priorityRank > :cRank or (w.priorityRank = :cRank"
						+ " and (w.updatedAt < :cAt or (w.updatedAt = :cAt and w.id < :cId))))";
			}
			case "created" -> {
				return "(w.createdAt < :cAt or (w.createdAt = :cAt and w.id < :cId))";
			}
			default -> {
				return "(w.updatedAt < :cAt or (w.updatedAt = :cAt and w.id < :cId))";
			}
		}
	}

	private static long number(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

	private static String escapeLike(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}
}
