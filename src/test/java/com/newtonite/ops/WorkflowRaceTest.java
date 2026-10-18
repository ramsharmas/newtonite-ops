package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Status changes are decided on the server from the item's current state. When two people
 * act on the same item at the same moment, exactly one action may take effect, and the
 * history must say so honestly.
 */
class WorkflowRaceTest extends ApiTestSupport {

	private static long countEvents(JsonNode history, String type) {
		return history.findValuesAsString("type").stream().filter(type::equals).count();
	}

	@Test
	void ifTheOwnerResolvesWhileALeadCancelsOnlyOneOfThemHappens() throws Exception {
		long id = createItem("Chen", "Payments", "Resolve or cancel").path("id").asLong();
		ok(post("Ben", "/api/items/" + id + "/claim", null));
		token("Ben");
		token("Asha");

		List<Callable<Reply>> calls = List.of(
				() -> post("Ben", "/api/items/" + id + "/actions/resolve", null),
				() -> post("Asha", "/api/items/" + id + "/actions/cancel", null));
		List<Reply> replies = atOnce(calls);

		assertThat(replies.stream().map(Reply::status)).containsExactlyInAnyOrder(200, 409);
		String status = detail("Asha", id).path("item").path("status").asString();
		assertThat(status).isIn("resolved", "cancelled");

		// Whoever won, the history records that and only that.
		JsonNode history = detail("Asha", id).path("history");
		assertThat(countEvents(history, "resolved") + countEvents(history, "cancelled")).isEqualTo(1);
		assertThat(countEvents(history, status.equals("resolved") ? "resolved" : "cancelled")).isEqualTo(1);
	}

	@Test
	void twoLeadsApprovingAtTheSameMomentLeaveExactlyOneApproval() throws Exception {
		long id = createItem("Chen", "Payments", "Approve me", Map.of("requiresApproval", true)).path("id").asLong();
		token("Asha");
		token("Farah");

		List<Callable<Reply>> calls = List.of(
				() -> post("Asha", "/api/items/" + id + "/actions/approve", null),
				() -> post("Farah", "/api/items/" + id + "/actions/approve", null));
		List<Reply> replies = atOnce(calls);

		assertThat(replies.stream().map(Reply::status)).containsExactlyInAnyOrder(200, 409);
		JsonNode detail = detail("Asha", id);
		assertThat(countEvents(detail.path("history"), "approved")).isEqualTo(1);
		assertThat(detail.path("item").path("approvedBy").path("name").asString()).isIn("Asha Rao", "Farah Haddad");
	}

	@Test
	void repeatingAnActionIsHarmless() {
		long id = createItem("Chen", "Payments", "Double click").path("id").asLong();
		ok(post("Ben", "/api/items/" + id + "/claim", null));

		assertThat(post("Ben", "/api/items/" + id + "/actions/resolve", null).status()).isEqualTo(200);
		Reply again = post("Ben", "/api/items/" + id + "/actions/resolve", null);

		assertThat(again.status()).isEqualTo(409);
		assertThat(again.error()).isEqualTo("invalid_transition");
		assertThat(countEvents(detail("Ben", id).path("history"), "resolved")).isEqualTo(1);
	}

	@Test
	void anEditStartedBeforeSomeoneTookTheItemIsRefusedNotLost() {
		long id = createItem("Chen", "Payments", "Edit while claiming").path("id").asLong();
		long versionChenSaw = detail("Chen", id).path("item").path("version").asLong();

		// Ben takes the item after Chen opened it. That is a change, so Chen's copy is out of date.
		ok(post("Ben", "/api/items/" + id + "/claim", null));
		Reply reply = patch("Chen", "/api/items/" + id, Map.of("version", versionChenSaw, "title", "Chen's new title"));

		assertThat(reply.status()).isEqualTo(409);
		assertThat(reply.error()).isEqualTo("conflict");
		assertThat(reply.body().path("current").path("assignee").path("name").asString()).isEqualTo("Ben Okafor");
		assertThat(detail("Chen", id).path("item").path("title").asString()).doesNotContain("Chen's new title");
	}
}
