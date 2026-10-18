package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** An item can live for a long time. Opening it must stay cheap, and older activity must stay reachable. */
class HistoryPagingTest extends ApiTestSupport {

	/** The ids of the history entries themselves (not the ids of the people inside them). */
	private static List<Long> eventIds(JsonNode history) {
		List<Long> ids = new ArrayList<>();
		history.forEach(event -> ids.add(event.path("id").asLong()));
		return ids;
	}

	private long itemWithComments(int comments) {
		long id = createItem("Chen", "Payments", "Long running").path("id").asLong();
		for (int i = 1; i <= comments; i++) {
			ok(post("Ben", "/api/items/" + id + "/comments", Map.of("body", "update " + i)));
		}
		return id;
	}

	@Test
	void openingAnItemReturnsOnlyTheNewestActivityAndSaysThereIsMore() {
		long id = itemWithComments(60);

		JsonNode detail = detail("Chen", id);

		assertThat(detail.path("history")).as("newest 50 events").hasSize(50);
		assertThat(detail.path("hasOlderHistory").asBoolean()).isTrue();
		assertThat(detail.path("historyTotal").asLong()).as("1 created + 60 comments").isEqualTo(61);
		assertThat(detail.path("commentsTotal").asLong()).isEqualTo(60);
		// The newest comment is there; the oldest is not.
		assertThat(detail.path("comments").findValuesAsString("body")).contains("update 60").doesNotContain("update 1");
		// History is oldest-first, like a log.
		assertThat(eventIds(detail.path("history"))).isSorted();
	}

	@Test
	void olderActivityCanBeFetchedPageByPageWithoutGapsOrRepeats() {
		long id = itemWithComments(60);
		JsonNode detail = detail("Chen", id);

		List<Long> everyEvent = new ArrayList<>(eventIds(detail.path("history")));
		Set<String> everyComment = new HashSet<>(detail.path("comments").findValuesAsString("body"));
		long oldest = everyEvent.getFirst();

		boolean more = detail.path("hasOlderHistory").asBoolean();
		int pages = 0;
		while (more) {
			JsonNode older = ok(get("Chen", "/api/items/" + id + "/history?limit=20&before=" + oldest));
			List<Long> ids = eventIds(older.path("history"));
			long boundary = oldest;
			assertThat(ids).allSatisfy(eventId -> assertThat(eventId).isLessThan(boundary));
			everyEvent.addAll(0, ids);
			everyComment.addAll(older.path("comments").findValuesAsString("body"));
			oldest = ids.getFirst();
			more = older.path("hasOlderHistory").asBoolean();
			assertThat(++pages).isLessThan(10);
		}

		assertThat(everyEvent).as("all 61 events, once each, in order").hasSize(61).doesNotHaveDuplicates().isSorted();
		assertThat(everyComment).hasSize(60);
	}

	@Test
	void aShortItemHasNothingOlder() {
		long id = itemWithComments(2);

		JsonNode detail = detail("Chen", id);

		assertThat(detail.path("hasOlderHistory").asBoolean()).isFalse();
		assertThat(detail.path("history")).hasSize(3);
		assertThat(detail.path("comments")).hasSize(2);
	}

	@Test
	void olderActivityFollowsTheSameAccessRulesAsTheItem() {
		long id = itemWithComments(3);

		assertThat(get("Erik", "/api/items/" + id + "/history").status()).as("Erik isn't in Payments").isEqualTo(404);
		assertThat(get("Erik", "/api/items/999999/history").status()).isEqualTo(404);
		assertThat(get("Ben", "/api/items/" + id + "/history").status()).isEqualTo(200);
	}
}
