package com.newtonite.ops.items;

import com.newtonite.ops.web.ApiException;

public enum Status {
	OPEN, IN_PROGRESS, RESOLVED, CLOSED, CANCELLED;

	/** The value used in the API, for example "in_progress". */
	public String key() {
		return name().toLowerCase();
	}

	/** For messages, for example "in progress". */
	public String label() {
		return key().replace('_', ' ');
	}

	public boolean isFinished() {
		return this == CLOSED || this == CANCELLED;
	}

	public static Status parse(String value) {
		for (Status status : values()) {
			if (status.key().equalsIgnoreCase(value)) {
				return status;
			}
		}
		throw ApiException.badRequest("Unknown status: " + value);
	}
}
