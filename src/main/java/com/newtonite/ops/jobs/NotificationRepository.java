package com.newtonite.ops.jobs;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	boolean existsByJobIdAndUserId(Long jobId, Long userId);

	long countByJobId(Long jobId);

	List<Notification> findTop20ByUserIdOrderByIdDesc(Long userId);

	long countByUserIdAndReadAtIsNull(Long userId);

	@Transactional
	@Modifying
	@Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
	int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
