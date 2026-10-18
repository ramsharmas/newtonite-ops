package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class AccessTest extends ApiTestSupport {

	@Test
	void signInScreenListsTheDemoPeopleWithTheirTeams() {
		JsonNode people = ok(get(null, "/api/users"));
		assertThat(people.size()).isEqualTo(6);

		JsonNode asha = person("Asha");
		assertThat(asha.path("teams").toString())
				.contains("\"name\":\"Payments\"", "\"role\":\"lead\"")
				.contains("\"name\":\"Support\"", "\"role\":\"member\"");

		JsonNode me = ok(get("Asha", "/api/me"));
		assertThat(me.path("name").asString()).isEqualTo("Asha Rao");
	}

	@Test
	void apiRefusesRequestsWithoutAValidSignIn() throws Exception {
		assertThat(get(null, "/api/items").status()).isEqualTo(401);

		HttpResponse<String> bogus = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/items"))
						.header("Authorization", "Bearer not-a-real-token").build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(bogus.statusCode()).isEqualTo(401);
	}

	@Test
	void peopleOutsideATeamCantSeeOrTouchItsItems() {
		long id = createItem("Chen", "Payments", "Payments only").path("id").asLong();

		// Erik is only in Platform. To him the item doesn't exist.
		assertThat(get("Erik", "/api/items/" + id).status()).isEqualTo(404);
		assertThat(post("Erik", "/api/items/" + id + "/claim", null).status()).isEqualTo(404);
		assertThat(post("Erik", "/api/items/" + id + "/comments", Map.of("body", "hi")).status()).isEqualTo(404);
		assertThat(patch("Erik", "/api/items/" + id, Map.of("version", 0, "title", "mine now")).status()).isEqualTo(404);
	}

	@Test
	void searchOnlyCoversYourOwnTeams() {
		String word = "zebra" + shortId();
		createItem("Erik", "Platform", "Platform " + word);

		assertThat(ok(get("Chen", "/api/items?q=" + word)).path("items").size()).isEqualTo(1);
		assertThat(ok(get("Asha", "/api/items?q=" + word)).path("items").size()).isZero();
	}

	@Test
	void youCantCreateItemsInATeamYouAreNotIn() {
		Reply reply = post("Erik", "/api/items", Map.of("teamId", teamId("Payments"), "title", "Sneaky"));
		assertThat(reply.status()).isEqualTo(404);
	}

	@Test
	void aSignInStopsWorkingAfterItsLifetime() {
		String fresh = token("Asha");
		assertThat(get("Asha", "/api/me").status()).isEqualTo(200);

		// Pretend this browser signed in 13 hours ago (the limit is 12).
		jdbc.update("update login_tokens set created_at = dateadd('HOUR', -13, current_timestamp) where token = ?", fresh);

		assertThat(get("Asha", "/api/me").status()).isEqualTo(401);
	}

	@Test
	void unknownAddressesAnswerInTheSameShapeAsEveryOtherError() {
		Reply reply = get("Asha", "/api/does-not-exist");
		assertThat(reply.status()).isEqualTo(404);
		assertThat(reply.error()).isEqualTo("not_found");
	}
}
