package com.newtonite.ops.auth;

/** A person's role inside one team. Someone can be a lead in one team and a member in another. */
public enum Role {
	MEMBER, LEAD;

	public String key() {
		return name().toLowerCase();
	}
}
