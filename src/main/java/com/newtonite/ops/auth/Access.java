package com.newtonite.ops.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/** Answers "is this person in that team, and as what?". Every item read and write starts here. */
@Service
public class Access {

	private final MembershipRepository memberships;

	public Access(MembershipRepository memberships) {
		this.memberships = memberships;
	}

	public Optional<Role> roleIn(long userId, long teamId) {
		return memberships.findByUserIdAndTeamId(userId, teamId).map(Membership::getRole);
	}

	public List<Long> teamIds(long userId) {
		return memberships.findByUserId(userId).stream().map(Membership::getTeamId).toList();
	}

	public List<Long> leadsOf(long teamId) {
		return memberships.findByTeamIdAndRole(teamId, Role.LEAD).stream().map(Membership::getUserId).toList();
	}
}
