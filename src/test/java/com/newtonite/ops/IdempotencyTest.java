package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

class IdempotencyTest extends ApiTestSupport {

	@Test
	void retryingACreateWithTheSameKeyMakesOneItem() {
		String title = "Double click " + shortId();
		Map<String, Object> body = Map.of("teamId", teamId("Payments"), "title", title);
		String key = UUID.randomUUID().toString();

		Reply first = send("Ben", "POST", "/api/items", body, key);
		Reply second = send("Ben", "POST", "/api/items", body, key);

		assertThat(first.status()).isEqualTo(201);
		assertThat(second.status()).isEqualTo(201);
		assertThat(second.body().path("id").asLong()).isEqualTo(first.body().path("id").asLong());
		assertThat(second.raw().headers().firstValue("Idempotent-Replayed")).hasValue("true");
		assertThat(search("Ben", title).size()).isEqualTo(1);
	}

	@Test
	void sameKeyArrivingTwiceAtOnceStillMakesOneItem() throws Exception {
		String title = "Flaky network " + shortId();
		Map<String, Object> body = Map.of("teamId", teamId("Payments"), "title", title);
		String key = UUID.randomUUID().toString();
		token("Ben");

		List<Callable<Reply>> requests = List.of(
				() -> send("Ben", "POST", "/api/items", body, key),
				() -> send("Ben", "POST", "/api/items", body, key));
		List<Reply> replies = atOnce(requests);

		assertThat(replies).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
		assertThat(replies.get(0).body().path("id").asLong()).isEqualTo(replies.get(1).body().path("id").asLong());
		assertThat(search("Ben", title).size()).isEqualTo(1);
	}

	@Test
	void retryingACommentWithTheSameKeyAddsOneComment() {
		long id = createItem("Ben", "Payments", "Chatty").path("id").asLong();
		String key = UUID.randomUUID().toString();

		send("Ben", "POST", "/api/items/" + id + "/comments", Map.of("body", "On it"), key);
		send("Ben", "POST", "/api/items/" + id + "/comments", Map.of("body", "On it"), key);

		assertThat(detail("Ben", id).path("comments").size()).isEqualTo(1);
	}

	@Test
	void reusingAKeyForADifferentRequestIsRefused() {
		long id = createItem("Ben", "Payments", "Key reuse").path("id").asLong();
		String key = UUID.randomUUID().toString();
		send("Ben", "POST", "/api/items/" + id + "/comments", Map.of("body", "first"), key);

		Reply reply = send("Ben", "POST", "/api/items", Map.of("teamId", teamId("Payments"), "title", "other"), key);
		assertThat(reply.status()).isEqualTo(422);
		assertThat(reply.error()).isEqualTo("key_reused");
	}
}
