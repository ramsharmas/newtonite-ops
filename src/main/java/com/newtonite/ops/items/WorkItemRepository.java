package com.newtonite.ops.items;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkItemRepository extends JpaRepository<WorkItem, Long> {

	/**
	 * Takes ownership only if nobody owns the item yet. The check and the write are one
	 * UPDATE statement, so when two people click at once the database lets exactly one
	 * of them through. Returns 0 if someone else got there first.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update WorkItem w
			   set w.assigneeId = :userId,
			       w.status = com.newtonite.ops.items.Status.IN_PROGRESS,
			       w.updatedAt = :now,
			       w.version = w.version + 1
			 where w.id = :id
			   and w.assigneeId is null
			   and w.status = com.newtonite.ops.items.Status.OPEN
			""")
	int claim(@Param("id") long id, @Param("userId") long userId, @Param("now") Instant now);
}
