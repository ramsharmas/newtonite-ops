package com.newtonite.ops.auth;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.newtonite.ops.web.ApiException;

/**
 * Demo sign-in: pick a person from a list, no password. Swapping this for real SSO
 * would only change this class; the rest of the app just needs a user per request.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

	public record LoginRequest(@NotNull Long userId) {
	}

	public record TeamRole(Long id, String name, String role) {
	}

	public record Person(Long id, String name, List<TeamRole> teams) {
	}

	public record LoginResponse(String token, Person user) {
	}

	private final UserRepository users;
	private final TeamRepository teams;
	private final MembershipRepository memberships;
	private final LoginTokenRepository tokens;

	public AuthController(UserRepository users, TeamRepository teams, MembershipRepository memberships,
			LoginTokenRepository tokens) {
		this.users = users;
		this.teams = teams;
		this.memberships = memberships;
		this.tokens = tokens;
	}

	@GetMapping("/users")
	@Transactional(readOnly = true)
	public List<Person> users() {
		return users.findAll().stream()
				.sorted(Comparator.comparing(User::getName))
				.map(this::person)
				.toList();
	}

	@PostMapping("/login")
	@Transactional
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		User user = users.findById(request.userId()).orElseThrow(() -> ApiException.notFound("User"));
		String token = UUID.randomUUID().toString();
		tokens.save(new LoginToken(token, user.getId(), Instant.now()));
		return new LoginResponse(token, person(user));
	}

	@PostMapping("/logout")
	@Transactional
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		AuthInterceptor.bearerToken(request).ifPresent(tokens::deleteById);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	@Transactional(readOnly = true)
	public Person me(@CurrentUser User me) {
		return person(me);
	}

	private Person person(User user) {
		Map<Long, String> teamNames = teams.findAll().stream()
				.collect(Collectors.toMap(Team::getId, Team::getName));
		List<TeamRole> roles = memberships.findByUserId(user.getId()).stream()
				.map(m -> new TeamRole(m.getTeamId(), teamNames.get(m.getTeamId()), m.getRole().key()))
				.sorted(Comparator.comparing(TeamRole::name))
				.toList();
		return new Person(user.getId(), user.getName(), roles);
	}
}
