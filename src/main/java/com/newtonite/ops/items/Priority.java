package com.newtonite.ops.items;

import com.newtonite.ops.web.ApiException;

/** Declared most urgent first, so {@link #rank()} sorts the right way. */
public enum Priority {
	URGENT, HIGH, MEDIUM, LOW;

	public String key() {
		return name().toLowerCase();
	}

	public int rank() {
		return ordinal();
	}

	public static Priority parse(String value) {
		for (Priority priority : values()) {
			if (priority.key().equalsIgnoreCase(value)) {
				return priority;
			}
		}
		throw ApiException.badRequest("Unknown priority: " + value);
	}
}
