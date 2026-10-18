package com.newtonite.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts the real app on a random port with an empty in-memory database (plus the demo
 * people and teams) and talks to it over HTTP, the same way the browser does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ApiTestSupport {

	public record Reply(int status, JsonNode body, HttpResponse<String> raw) {

		public String error() {
			return body.path("error").asString();
		}
	}

	@LocalServerPort
	int port;

	@Autowired
	ObjectMapper json;

	/** For setting up situations the API can't create, such as an item nobody has touched for days. */
	@Autowired
	JdbcTemplate jdbc;

	private final HttpClient http = HttpClient.newHttpClient();
	private final Map<String, String> tokens = new ConcurrentHashMap<>();

	// ---- HTTP -------------------------------------------------------------------------

	protected Reply send(String user, String method, String path, Object body, String idempotencyKey) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.method(method, body == null
						? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
		if (user != null) {
			request.header("Authorization", "Bearer " + token(user));
		}
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		try {
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			JsonNode parsed = response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
			return new Reply(response.statusCode(), parsed, response);
		}
		catch (Exception e) {
			throw new IllegalStateException(method + " " + path + " failed", e);
		}
	}

	protected Reply get(String user, String path) {
		return send(user, "GET", path, null, null);
	}

	protected Reply post(String user, String path, Object body) {
		return send(user, "POST", path, body, null);
	}

	protected Reply patch(String user, String path, Object body) {
		return send(user, "PATCH", path, body, null);
	}

	protected JsonNode ok(Reply reply) {
		assertThat(reply.status()).as(reply.body().toString()).isBetween(200, 299);
		return reply.body();
	}

	// ---- People and items -------------------------------------------------------------

	/** Signs in once per first name ("Asha", "Ben", ...) and remembers the token. */
	protected String token(String firstName) {
		return tokens.computeIfAbsent(firstName, name -> {
			long id = person(name).path("id").asLong();
			return ok(send(null, "POST", "/api/login", Map.of("userId", id), null)).path("token").asString();
		});
	}

	protected JsonNode person(String firstName) {
		for (JsonNode person : ok(get(null, "/api/users"))) {
			if (person.path("name").asString().startsWith(firstName + " ")) {
				return person;
			}
		}
		throw new IllegalArgumentException("No demo user called " + firstName);
	}

	protected long userId(String firstName) {
		return person(firstName).path("id").asLong();
	}

	protected long teamId(String teamName) {
		for (JsonNode person : ok(get(null, "/api/users"))) {
			for (JsonNode team : person.path("teams")) {
				if (team.path("name").asString().equals(teamName)) {
					return team.path("id").asLong();
				}
			}
		}
		throw new IllegalArgumentException("No team called " + teamName);
	}

	/** Creates an item and returns it. Titles get a random suffix so tests don't see each other's items. */
	protected JsonNode createItem(String user, String team, String title, Map<String, Object> extra) {
		Map<String, Object> body = new HashMap<>(extra);
		body.put("teamId", teamId(team));
		body.put("title", title + " " + shortId());
		return ok(post(user, "/api/items", body));
	}

	protected JsonNode createItem(String user, String team, String title) {
		return createItem(user, team, title, Map.of());
	}

	protected JsonNode detail(String user, long itemId) {
		return ok(get(user, "/api/items/" + itemId));
	}

	protected JsonNode search(String user, String text) {
		return ok(get(user, "/api/items?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8))).path("items");
	}

	protected static String shortId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	/** Runs all the calls at the same moment and returns their replies in order. */
	protected List<Reply> atOnce(List<Callable<Reply>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Reply>> futures = calls.stream()
					.map(call -> pool.submit(() -> {
						start.await();
						return call.call();
					}))
					.toList();
			start.countDown();
			return futures.stream().map(f -> {
				try {
					return f.get();
				}
				catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).toList();
		}
		finally {
			pool.shutdownNow();
		}
	}
}
