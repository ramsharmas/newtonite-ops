package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class ClaimTest extends ApiTestSupport {

	@Test
	void claimingMakesYouTheOwnerAndStartsTheWork() {
		long id = createItem("Chen", "Payments", "Unowned").path("id").asLong();

		JsonNode item = ok(post("Ben", "/api/items/" + id + "/claim", null));
		assertThat(item.path("assignee").path("name").asString()).isEqualTo("Ben Okafor");
		assertThat(item.path("status").asString()).isEqualTo("in_progress");
		assertThat(item.path("version").asLong()).isEqualTo(1);
	}

	@Test
	void secondPersonIsToldWhoAlreadyOwnsIt() {
		long id = createItem("Chen", "Payments", "Popular").path("id").asLong();
		ok(post("Ben", "/api/items/" + id + "/claim", null));

		Reply reply = post("Chen", "/api/items/" + id + "/claim", null);
		assertThat(reply.status()).isEqualTo(409);
		assertThat(reply.error()).isEqualTo("already_claimed");
		assertThat(reply.body().path("message").asString()).isEqualTo("Already taken by Ben Okafor");
		assertThat(reply.body().path("owner").path("name").asString()).isEqualTo("Ben Okafor");
	}

	@Test
	void sixClaimsAtTheSameMomentExactlyOneWins() throws Exception {
		long id = createItem("Chen", "Payments", "Race to claim").path("id").asLong();
		List<String> people = List.of("Asha", "Ben", "Chen", "Farah", "Ben", "Chen");
		people.forEach(this::token);

		List<Callable<Reply>> claims = people.stream()
				.<Callable<Reply>>map(person -> () -> post(person, "/api/items/" + id + "/claim", null))
				.toList();
		List<Reply> replies = atOnce(claims);

		assertThat(replies).filteredOn(r -> r.status() == 200).hasSize(1);
		assertThat(replies).filteredOn(r -> r.status() == 409).hasSize(5);

		JsonNode history = detail("Asha", id).path("history");
		assertThat(history.findValuesAsString("type")).containsExactly("created", "claimed");
	}

	@Test
	void onlyTheOwnerOrALeadCanReleaseAnItem() {
		long id = createItem("Chen", "Payments", "Release me").path("id").asLong();
		ok(post("Ben", "/api/items/" + id + "/claim", null));

		assertThat(post("Chen", "/api/items/" + id + "/actions/release", null).status()).isEqualTo(403);

		JsonNode released = ok(post("Asha", "/api/items/" + id + "/actions/release", null));
		assertThat(released.path("status").asString()).isEqualTo("open");
		assertThat(released.path("assignee").isNull()).isTrue();
	}
}
