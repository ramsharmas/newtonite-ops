package com.newtonite.ops.jobs;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, Long> {

	@Query("""
			select j.id from Job j
			 where j.status = com.newtonite.ops.jobs.JobStatus.PENDING
			   and j.runAfter <= :now
			 order by j.id
			""")
	List<Long> findDue(@Param("now") Instant now, Pageable page);
}
