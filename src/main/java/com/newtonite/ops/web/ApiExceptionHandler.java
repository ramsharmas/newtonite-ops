package com.newtonite.ops.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, Object>> api(ApiException e) {
		Map<String, Object> body = body(e.getCode(), e.getMessage());
		body.putAll(e.getExtra());
		return ResponseEntity.status(e.getStatus()).body(body);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
		FieldError field = e.getBindingResult().getFieldError();
		String message = field == null ? "Request is not valid" : field.getField() + " " + field.getDefaultMessage();
		return ResponseEntity.badRequest().body(body("invalid", message));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<Map<String, Object>> unreadable(Exception e) {
		return ResponseEntity.badRequest().body(body("invalid", "Request is not valid"));
	}

	// Unknown URLs and wrong HTTP methods get the same error shape as everything else.
	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<Map<String, Object>> noSuchPath(NoResourceFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("not_found", "Nothing at this address"));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<Map<String, Object>> wrongMethod(HttpRequestMethodNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body("method_not_allowed", e.getMessage()));
	}

	// Two requests changed the same row at the same moment and the database refused the second one.
	// Item endpoints catch this themselves to include the current item; this is the fallback.
	@ExceptionHandler(ConcurrencyFailureException.class)
	ResponseEntity<Map<String, Object>> concurrent(ConcurrencyFailureException e) {
		log.info("Concurrent update refused: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(body("conflict", "Someone else changed this at the same moment. Reload and try again."));
	}

	private static Map<String, Object> body(String code, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", code);
		body.put("message", message);
		return body;
	}
}
