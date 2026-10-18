package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class ApprovalTest extends ApiTestSupport {

	@Test
	void itemThatNeedsApprovalCantBeResolvedWithoutIt() {
		long id = refund("Chen");
		ok(post("Ben", "/api/items/" + id + "/claim", null));

		Reply reply = post("Ben", "/api/items/" + id + "/actions/resolve", null);
		assertThat(reply.status()).isEqualTo(409);
		assertThat(reply.error()).isEqualTo("needs_approval");
	}

	@Test
	void membersCantApprove() {
		long id = refund("Chen");
		Reply reply = post("Ben", "/api/items/" + id + "/actions/approve", null);
		assertThat(reply.status()).isEqualTo(403);
	}

	@Test
	void leadsCantApproveTheirOwnWork() {
		long raisedByAsha = refund("Asha");
		assertThat(post("Asha", "/api/items/" + raisedByAsha + "/actions/approve", null).error()).isEqualTo("own_work");

		long ownedByAsha = refund("Chen");
		ok(post("Asha", "/api/items/" + ownedByAsha + "/claim", null));
		assertThat(post("Asha", "/api/items/" + ownedByAsha + "/actions/approve", null).error()).isEqualTo("own_work");
	}

	@Test
	void approvedItemCanBeResolvedThenClosedByALead() {
		long id = refund("Chen");
		ok(post("Ben", "/api/items/" + id + "/claim", null));

		JsonNode approved = ok(post("Asha", "/api/items/" + id + "/actions/approve", null));
		assertThat(approved.path("approvedBy").path("name").asString()).isEqualTo("Asha Rao");

		assertThat(ok(post("Ben", "/api/items/" + id + "/actions/resolve", null)).path("status").asString())
				.isEqualTo("resolved");
		assertThat(post("Ben", "/api/items/" + id + "/actions/close", null).status()).isEqualTo(403);
		assertThat(ok(post("Farah", "/api/items/" + id + "/actions/close", null)).path("status").asString())
				.isEqualTo("closed");
	}

	@Test
	void onlyLeadsCanCancelAndCancelledItemsCantBeEdited() {
		long id = createItem("Ben", "Payments", "Not needed").path("id").asLong();

		assertThat(post("Ben", "/api/items/" + id + "/actions/cancel", null).status()).isEqualTo(403);
		JsonNode cancelled = ok(post("Asha", "/api/items/" + id + "/actions/cancel", null));
		assertThat(cancelled.path("status").asString()).isEqualTo("cancelled");

		Reply edit = patch("Ben", "/api/items/" + id, Map.of("version", cancelled.path("version").asLong(), "title", "x"));
		assertThat(edit.status()).isEqualTo(409);
		assertThat(edit.error()).isEqualTo("finished");
	}

	@Test
	void itemPageSaysWhichActionsYouCanTakeAndWhyNot() {
		long id = refund("Chen");

		JsonNode actions = detail("Ben", id).path("actions");
		assertThat(action(actions, "claim").path("allowed").asBoolean()).isTrue();
		assertThat(action(actions, "approve").path("allowed").asBoolean()).isFalse();
		assertThat(action(actions, "approve").path("reason").asString()).isEqualTo("Only a team lead can approve items");
	}

	private long refund(String raisedBy) {
		return createItem(raisedBy, "Payments", "Refund EUR 240",
				Map.of("priority", "urgent", "requiresApproval", true)).path("id").asLong();
	}

	private static JsonNode action(JsonNode actions, String name) {
		for (JsonNode action : actions) {
			if (action.path("name").asString().equals(name)) {
				return action;
			}
		}
		throw new AssertionError("No action " + name);
	}
}
