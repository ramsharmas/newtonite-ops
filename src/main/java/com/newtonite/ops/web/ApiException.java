package com.newtonite.ops.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * An error the client should see. The handler turns it into
 * {@code {"error": code, "message": message, ...extra}} with the given status.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final Map<String, Object> extra = new LinkedHashMap<>();

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public static ApiException notFound(String what) {
		return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found");
	}

	public static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "invalid", message);
	}

	/** Adds a field to the error body, for example the current version of an item after a conflict. */
	public ApiException with(String key, Object value) {
		extra.put(key, value);
		return this;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public Map<String, Object> getExtra() {
		return extra;
	}
}
