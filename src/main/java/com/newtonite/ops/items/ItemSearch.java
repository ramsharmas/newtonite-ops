package com.newtonite.ops.items;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.newtonite.ops.web.ApiException;

/** The item list query. Built by hand because every filter is optional. */
@Repository
public class ItemSearch {

	/**
	 * All fields are optional.
	 *
	 * @param q        words to find in the title or description
	 * @param status   a status, or "active" for open and in progress
	 * @param assigned "me" or "unassigned"
	 * @param sort     "priority", "created" or "updated" (the default)
	 */
	public record Filter(String q, Long teamId, String status, String assigned, String sort) {
	}

	@PersistenceContext
	private EntityManager em;

	public List<WorkItem> find(long userId, Collection<Long> teamIds, Filter filter, int limit) {
		if (teamIds.isEmpty()) {
			return List.of();
		}
		// Only the caller's teams, always. The other filters narrow it further.
		StringBuilder jpql = new StringBuilder("select w from WorkItem w where w.teamId in :teams");
		Map<String, Object> params = new HashMap<>();
		params.put("teams", teamIds);

		if (StringUtils.hasText(filter.q())) {
			jpql.append(" and (lower(w.title) like :q escape '!' or lower(w.description) like :q escape '!')");
			params.put("q", "%" + escapeLike(filter.q().trim().toLowerCase()) + "%");
		}
		if (filter.teamId() != null) {
			jpql.append(" and w.teamId = :teamId");
			params.put("teamId", filter.teamId());
		}
		if ("active".equals(filter.status())) {
			jpql.append(" and w.status in :statuses");
			params.put("statuses", List.of(Status.OPEN, Status.IN_PROGRESS));
		}
		else if (StringUtils.hasText(filter.status())) {
			jpql.append(" and w.status = :status");
			params.put("status", Status.parse(filter.status()));
		}
		if ("me".equals(filter.assigned())) {
			jpql.append(" and w.assigneeId = :me");
			params.put("me", userId);
		}
		else if ("unassigned".equals(filter.assigned())) {
			jpql.append(" and w.assigneeId is null");
		}
		else if (StringUtils.hasText(filter.assigned())) {
			throw ApiException.badRequest("assigned must be 'me' or 'unassigned'");
		}

		jpql.append(switch (filter.sort() == null ? "updated" : filter.sort()) {
			case "priority" -> " order by w.priorityRank, w.updatedAt desc";
			case "created" -> " order by w.createdAt desc";
			case "updated" -> " order by w.updatedAt desc";
			default -> throw ApiException.badRequest("sort must be 'priority', 'created' or 'updated'");
		});

		TypedQuery<WorkItem> query = em.createQuery(jpql.toString(), WorkItem.class);
		params.forEach(query::setParameter);
		return query.setMaxResults(limit).getResultList();
	}

	private static String escapeLike(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}
}
