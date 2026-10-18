package com.newtonite.ops.web;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.newtonite.ops.auth.User;

import tools.jackson.databind.ObjectMapper;

/**
 * Makes "create" requests safe to repeat. The browser sends an Idempotency-Key header
 * with each new item or comment; a double click or a network retry sends the same key
 * again and gets the first response back instead of creating a duplicate.
 */
@Service
public class Idempotency {

	private static final int MAX_KEY_LENGTH = 100;

	private final IdempotencyRepository records;
	private final TransactionTemplate tx;
	private final ObjectMapper json;

	public Idempotency(IdempotencyRepository records, PlatformTransactionManager transactionManager, ObjectMapper json) {
		this.records = records;
		this.tx = new TransactionTemplate(transactionManager);
		this.json = json;
	}

	public ResponseEntity<?> run(User me, String key, String route, Supplier<ResponseEntity<?>> action) {
		if (key == null || key.isBlank()) {
			return action.get();
		}
		if (key.length() > MAX_KEY_LENGTH) {
			throw ApiException.badRequest("Idempotency-Key is too long");
		}

		Optional<IdempotencyRecord> earlier = records.findByUserIdAndIdemKey(me.getId(), key);
		if (earlier.isPresent()) {
			return replay(earlier.get(), route);
		}
		try {
			// The change and the saved response commit together. If the response can't be
			// saved, the change is rolled back too.
			return tx.execute(status -> {
				ResponseEntity<?> response = action.get();
				records.saveAndFlush(new IdempotencyRecord(me.getId(), key, route, response.getStatusCode().value(),
						json.writeValueAsString(response.getBody()), Instant.now()));
				return response;
			});
		}
		catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
			// Both copies of the request arrived at the same moment and the other one won.
			// Ours was rolled back; answer with what the winner returned.
			return records.findByUserIdAndIdemKey(me.getId(), key)
					.map(record -> replay(record, route))
					.orElseThrow(() -> e);
		}
	}

	private ResponseEntity<?> replay(IdempotencyRecord record, String route) {
		if (!record.getRoute().equals(route)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "key_reused",
					"This Idempotency-Key was already used for a different request");
		}
		return ResponseEntity.status(record.getStatus())
				.header("Idempotent-Replayed", "true")
				.contentType(MediaType.APPLICATION_JSON)
				.body(record.getBody());
	}
}
