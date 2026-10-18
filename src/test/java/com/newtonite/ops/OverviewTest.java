package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * The overview cards tell people what needs attention. A card is only useful if its number is
 * exactly what they find when they click it, and if it never counts work they can't see.
 */
class OverviewTest extends ApiTestSupport {

	private long total(String user, String query) {
		return ok(get(user, "/api/items?limit=1&" + query)).path("total").asLong();
	}

	private JsonNode overview(String user) {
		return ok(get(user, "/api/items/overview"));
	}

	private void backdate(long itemId, int hours) {
		jdbc.update("update work_items set updated_at = dateadd('HOUR', ?, current_timestamp) where id = ?", -hours, itemId);
	}

	@Test
	void everyCardEqualsTheListItOpens() {
		// A mix of states in Payments, seen by Asha (a Payments lead).
		long unowned = createItem("Chen", "Payments", "Overview unowned", Map.of("priority", "urgent")).path("id").asLong();
		createItem("Chen", "Payments", "Overview approval", Map.of("requiresApproval", true));
		long mine = createItem("Chen", "Payments", "Overview mine").path("id").asLong();
		ok(post("Asha", "/api/items/" + mine + "/claim", null));
		long old = createItem("Chen", "Payments", "Overview old").path("id").asLong();
		backdate(old, 72);
		long finished = createItem("Chen", "Payments", "Overview finished", Map.of("requiresApproval", true)).path("id").asLong();
		backdate(finished, 72);
		ok(post("Farah", "/api/items/" + finished + "/actions/cancel", null));
		backdate(finished, 72);

		for (String user : new String[] { "Asha", "Ben", "Chen", "Divya", "Erik", "Farah" }) {
			JsonNode card = overview(user);
			assertThat(card.path("active").asLong()).as(user + " active").isEqualTo(total(user, "status=active"));
			assertThat(card.path("mine").asLong()).as(user + " mine").isEqualTo(total(user, "status=active&assigned=me"));
			assertThat(card.path("unassigned").asLong()).as(user + " unassigned").isEqualTo(total(user, "status=active&assigned=unassigned"));
			assertThat(card.path("urgent").asLong()).as(user + " urgent").isEqualTo(total(user, "status=active&priority=urgent"));
			assertThat(card.path("needsApproval").asLong()).as(user + " needs approval").isEqualTo(total(user, "attention=approval"));
			assertThat(card.path("stale").asLong()).as(user + " stale").isEqualTo(total(user, "attention=stale"));
		}

		// And the items we made land where we expect.
		assertThat(overview("Asha").path("mine").asLong()).isGreaterThanOrEqualTo(1);
		assertThat(search("Asha", "Overview unowned").get(0).path("id").asLong()).isEqualTo(unowned);
		assertThat(ok(get("Asha", "/api/items?attention=approval&q=Overview")).path("items").findValuesAsString("title").toString())
				.contains("Overview approval")
				.doesNotContain("Overview finished");
	}

	@Test
	void cardsNeverCountOtherTeamsWork() {
		JsonNode before = overview("Asha");

		createItem("Erik", "Platform", "Platform only", Map.of("priority", "urgent", "requiresApproval", true));

		JsonNode after = overview("Asha");
		assertThat(after).as("Asha isn't in Platform, so nothing changes for her").isEqualTo(before);
		assertThat(overview("Erik").path("urgent").asLong()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void theTeamFilterNarrowsCardsLikeItNarrowsTheList() {
		long payments = teamId("Payments");
		long platform = teamId("Platform");
		createItem("Chen", "Payments", "Team filter");

		assertThat(ok(get("Chen", "/api/items/overview?teamId=" + payments)).path("active").asLong())
				.isEqualTo(total("Chen", "status=active&teamId=" + payments));
		assertThat(ok(get("Chen", "/api/items/overview?teamId=" + platform)).path("active").asLong())
				.isEqualTo(total("Chen", "status=active&teamId=" + platform));

		// A team you aren't in is just empty.
		JsonNode outsider = ok(get("Erik", "/api/items/overview?teamId=" + payments));
		assertThat(outsider.path("active").asLong()).isZero();
		assertThat(outsider.path("stale").asLong()).isZero();
	}

	@Test
	void anItemIsStaleWhenNothingHappenedAndACommentCountsAsHappening() {
		long id = createItem("Chen", "Payments", "Forgotten").path("id").asLong();
		backdate(id, 48);
		long version = detail("Asha", id).path("item").path("version").asLong();

		assertThat(ok(get("Asha", "/api/items?attention=stale&q=Forgotten")).path("items").findValues("id"))
				.extracting(JsonNode::asLong).contains(id);

		ok(post("Ben", "/api/items/" + id + "/comments", Map.of("body", "Looking into it")));

		assertThat(ok(get("Asha", "/api/items?attention=stale&q=Forgotten")).path("items").findValues("id"))
				.extracting(JsonNode::asLong).doesNotContain(id);
		assertThat(detail("Asha", id).path("item").path("version").asLong())
				.as("a comment is activity, but it must not make anybody's edit conflict")
				.isEqualTo(version);
	}

	@Test
	void finishedWorkIsNeverStaleOrWaitingForApproval() {
		String word = "longago" + shortId();
		long id = createItem("Chen", "Payments", word, Map.of("requiresApproval", true)).path("id").asLong();
		ok(post("Farah", "/api/items/" + id + "/actions/cancel", null));
		backdate(id, 500);

		assertThat(ok(get("Asha", "/api/items?attention=stale&status=cancelled&q=" + word)).path("items")).isEmpty();
		assertThat(ok(get("Asha", "/api/items?attention=approval&q=" + word)).path("items")).isEmpty();
		// It is still there, just not something that needs attention.
		assertThat(ok(get("Asha", "/api/items?status=cancelled&q=" + word)).path("items")).hasSize(1);
	}

	@Test
	void unknownFilterValuesAreRefusedClearly() {
		assertThat(get("Asha", "/api/items?attention=nope").status()).isEqualTo(400);
		assertThat(get("Asha", "/api/items?priority=nope").status()).isEqualTo(400);
		assertThat(get("Asha", "/api/items?q=" + "x".repeat(300)).status()).isEqualTo(400);
	}
}
