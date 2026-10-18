package com.newtonite.ops.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

	List<Membership> findByUserId(Long userId);

	Optional<Membership> findByUserIdAndTeamId(Long userId, Long teamId);

	List<Membership> findByTeamIdAndRole(Long teamId, Role role);
}
