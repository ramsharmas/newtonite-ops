package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;

/** The list is paged with a cursor. Paging must never repeat an item, skip one, or show another team's. */
class PagingTest extends ApiTestSupport {

	private static final List<String> PRIORITIES = List.of("urgent", "high", "medium", "low");

	/** Makes 25 Platform items that share one search word and returns that word. */
	private String twentyFiveItems() {
		String word = "paged" + shortId();
		for (int i = 0; i < 25; i++) {
			createItem("Erik", "Platform", word + " item " + i, Map.of("priority", PRIORITIES.get(i % 4)));
		}
		return word;
	}

	/** Reads every page of a search, 10 at a time, and returns the ids in the order they came. */
	private List<Long> readAllPages(String user, String query) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			String path = "/api/items?limit=10&" + query + (cursor == null ? "" : "&cursor=" + cursor);
			JsonNode page = ok(get(user, path));
			assertThat(page.path("total").asLong()).as("total stays the same on every page").isEqualTo(25);
			page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
			pages++;
			assertThat(pages).as("paging must end").isLessThan(10);
		}
		while (cursor != null);
		assertThat(pages).isEqualTo(3);
		return ids;
	}

	@ParameterizedTest
	@ValueSource(strings = { "updated", "created", "priority" })
	void everyItemAppearsExactlyOnceWhateverTheSortOrder(String sort) {
		String word = twentyFiveItems();

		List<Long> ids = readAllPages("Erik", "q=" + word + "&sort=" + sort);

		assertThat(ids).hasSize(25);
		assertThat(new HashSet<>(ids)).as("no item repeated").hasSize(25);
	}

	@ParameterizedTest
	@ValueSource(strings = { "updated", "created", "priority" })
	void itemsWithIdenticalTimestampsAreStillPagedCompletely(String sort) {
		String word = twentyFiveItems();
		// Worst case for a cursor: every sort value is the same, so only the id breaks ties.
		jdbc.update("update work_items set created_at = TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00',"
				+ " updated_at = TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00' where title like ?", word + "%");

		List<Long> ids = readAllPages("Erik", "q=" + word + "&sort=" + sort);

		assertThat(new HashSet<>(ids)).hasSize(25);
	}

	@Test
	void prioritySortGoesMostUrgentFirstAcrossPages() {
		String word = twentyFiveItems();

		List<Integer> ranks = new ArrayList<>();
		String cursor = null;
		do {
			JsonNode page = ok(get("Erik", "/api/items?limit=10&sort=priority&q=" + word + (cursor == null ? "" : "&cursor=" + cursor)));
			page.path("items").forEach(item -> ranks.add(PRIORITIES.indexOf(item.path("priority").asString())));
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
		}
		while (cursor != null);

		assertThat(ranks).isSorted();
	}

	@Test
	void newestFirstIsTheDefaultOrder() {
		String word = "order" + shortId();
		long first = createItem("Erik", "Platform", word + " first").path("id").asLong();
		long second = createItem("Erik", "Platform", word + " second").path("id").asLong();

		JsonNode items = search("Erik", word);

		assertThat(items.get(0).path("id").asLong()).isEqualTo(second);
		assertThat(items.get(1).path("id").asLong()).isEqualTo(first);
	}

	@Test
	void pagesOnlyEverContainTheCallersTeams() {
		String word = twentyFiveItems();

		// Asha isn't in Platform: she gets an empty, correctly-shaped page, not an error and not a leak.
		JsonNode page = ok(get("Asha", "/api/items?limit=10&q=" + word));
		assertThat(page.path("items")).isEmpty();
		assertThat(page.path("total").asLong()).isZero();
		assertThat(page.path("nextCursor").isNull()).isTrue();
	}

	@Test
	void aCursorFromElsewhereOrMadeUpIsRefused() {
		String word = twentyFiveItems();
		String cursor = ok(get("Erik", "/api/items?limit=5&sort=updated&q=" + word)).path("nextCursor").asString();

		assertThat(get("Erik", "/api/items?sort=priority&cursor=" + cursor + "&q=" + word).status()).isEqualTo(400);
		assertThat(get("Erik", "/api/items?cursor=not-a-cursor").status()).isEqualTo(400);
		assertThat(get("Erik", "/api/items?cursor=" + java.util.Base64.getUrlEncoder().encodeToString("updated|x|y|z".getBytes())).status())
				.isEqualTo(400);
	}

	@Test
	void pageSizeIsClampedNotTrusted() {
		String word = twentyFiveItems();

		assertThat(ok(get("Erik", "/api/items?limit=0&q=" + word)).path("items")).hasSize(1);
		assertThat(ok(get("Erik", "/api/items?limit=-5&q=" + word)).path("items")).hasSize(1);
		assertThat(ok(get("Erik", "/api/items?limit=100000&q=" + word)).path("items")).hasSize(25);
		assertThat(ok(get("Erik", "/api/items?q=" + word)).path("items")).as("default page").hasSize(25);
	}

	@Test
	void filtersAndPagingWorkTogether() {
		String word = twentyFiveItems();

		Set<String> priorities = new HashSet<>();
		JsonNode page = ok(get("Erik", "/api/items?limit=3&priority=urgent&q=" + word));
		page.path("items").forEach(item -> priorities.add(item.path("priority").asString()));

		assertThat(priorities).containsExactly("urgent");
		assertThat(page.path("total").asLong()).as("7 of the 25 are urgent").isEqualTo(7);
		assertThat(page.path("nextCursor").isNull()).isFalse();
	}
}
