package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class ItemEditingTest extends ApiTestSupport {

	@Test
	void newItemStartsOpenAndHasACreatedEntryInHistory() {
		JsonNode item = createItem("Ben", "Payments", "Refund not received", Map.of("priority", "high"));
		assertThat(item.path("status").asString()).isEqualTo("open");
		assertThat(item.path("priority").asString()).isEqualTo("high");
		assertThat(item.path("version").asLong()).isZero();
		assertThat(item.path("createdBy").path("name").asString()).isEqualTo("Ben Okafor");

		JsonNode history = detail("Ben", item.path("id").asLong()).path("history");
		assertThat(history.size()).isEqualTo(1);
		assertThat(history.get(0).path("type").asString()).isEqualTo("created");
	}

	@Test
	void searchLooksInTitleAndDescription() {
		String word = "walrus" + shortId();
		createItem("Ben", "Payments", "Something", Map.of("description", "mentions " + word + " here"));

		JsonNode found = ok(get("Ben", "/api/items?q=" + word.toUpperCase())).path("items");
		assertThat(found.size()).isEqualTo(1);
	}

	@Test
	void listCanBeSortedByPriority() {
		String tag = "sort" + shortId();
		createItem("Ben", "Payments", tag + " low", Map.of("priority", "low"));
		createItem("Ben", "Payments", tag + " urgent", Map.of("priority", "urgent"));
		createItem("Ben", "Payments", tag + " medium", Map.of("priority", "medium"));

		JsonNode items = ok(get("Ben", "/api/items?sort=priority&q=" + tag)).path("items");
		assertThat(items.findValuesAsString("priority")).containsExactly("urgent", "medium", "low");
	}

	@Test
	void saveWithTheCurrentVersionWorksAndBumpsTheVersion() {
		long id = createItem("Ben", "Payments", "Typo in titel").path("id").asLong();

		JsonNode saved = ok(patch("Ben", "/api/items/" + id, Map.of("version", 0, "title", "Typo in title")));
		assertThat(saved.path("title").asString()).isEqualTo("Typo in title");
		assertThat(saved.path("version").asLong()).isEqualTo(1);

		JsonNode last = lastEvent("Ben", id);
		assertThat(last.path("type").asString()).isEqualTo("updated");
		assertThat(last.path("detail").path("changes").path("title").path("to").asString()).isEqualTo("Typo in title");
	}

	@Test
	void saveBasedOnAnOldVersionIsRefusedAndReturnsTheCurrentItem() {
		long id = createItem("Ben", "Payments", "Shared item").path("id").asLong();
		ok(patch("Ben", "/api/items/" + id, Map.of("version", 0, "priority", "low")));

		// Chen opened the item before Ben's save, so Chen still has version 0.
		Reply reply = patch("Chen", "/api/items/" + id, Map.of("version", 0, "title", "Chen's title"));
		assertThat(reply.status()).isEqualTo(409);
		assertThat(reply.error()).isEqualTo("conflict");
		assertThat(reply.body().path("current").path("priority").asString()).isEqualTo("low");
		assertThat(reply.body().path("current").path("version").asLong()).isEqualTo(1);

		// Re-applying only Chen's own field on top of the new version keeps Ben's change.
		JsonNode merged = ok(patch("Chen", "/api/items/" + id, Map.of("version", 1, "title", "Chen's title")));
		assertThat(merged.path("title").asString()).isEqualTo("Chen's title");
		assertThat(merged.path("priority").asString()).isEqualTo("low");
	}

	@Test
	void twoSavesAtTheSameMomentOnlyOneWins() throws Exception {
		long id = createItem("Ben", "Payments", "Race to edit").path("id").asLong();

		List<Callable<Reply>> saves = List.of(
				() -> patch("Ben", "/api/items/" + id, Map.of("version", 0, "priority", "urgent")),
				() -> patch("Chen", "/api/items/" + id, Map.of("version", 0, "priority", "low")));
		List<Integer> statuses = atOnce(saves).stream().map(Reply::status).toList();

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat(detail("Ben", id).path("item").path("version").asLong()).isEqualTo(1);
	}

	@Test
	void commentsShowUpOnTheItemAndInHistory() {
		long id = createItem("Ben", "Payments", "Needs discussion").path("id").asLong();

		Reply reply = post("Chen", "/api/items/" + id + "/comments", Map.of("body", "I can take this tomorrow"));
		assertThat(reply.status()).isEqualTo(201);

		JsonNode detail = detail("Ben", id);
		assertThat(detail.path("comments").get(0).path("body").asString()).isEqualTo("I can take this tomorrow");
		assertThat(detail.path("comments").get(0).path("author").path("name").asString()).isEqualTo("Chen Wei");
		assertThat(lastEvent("Ben", id).path("type").asString()).isEqualTo("commented");
		// A comment is not an edit, so it must not cause conflicts for people editing.
		assertThat(detail.path("item").path("version").asLong()).isZero();
	}

	private JsonNode lastEvent(String user, long id) {
		JsonNode history = detail(user, id).path("history");
		return history.get(history.size() - 1);
	}
}
