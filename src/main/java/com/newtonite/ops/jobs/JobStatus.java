package com.newtonite.ops.jobs;

public enum JobStatus {
	PENDING,
	DONE,
	/** Failed too many times. Kept so someone can look at last_error and retry it by hand. */
	DEAD
}
