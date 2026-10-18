package com.newtonite.ops.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.newtonite.ops.web.ApiException;

/** Looks up the bearer token on every /api call and remembers who is calling. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

	static final String CURRENT_USER = AuthInterceptor.class.getName() + ".user";

	private final LoginTokenRepository tokens;
	private final UserRepository users;
	private final Duration tokenLifetime;

	public AuthInterceptor(LoginTokenRepository tokens, UserRepository users,
			@Value("${newtonite.auth.token-ttl-hours:12}") long tokenTtlHours) {
		this.tokens = tokens;
		this.users = users;
		this.tokenLifetime = Duration.ofHours(tokenTtlHours);
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// A sign-in stops working after a while, so a forgotten browser doesn't stay signed in forever.
		User user = bearerToken(request)
				.flatMap(tokens::findById)
				.filter(token -> token.getCreatedAt().plus(tokenLifetime).isAfter(Instant.now()))
				.flatMap(token -> users.findById(token.getUserId()))
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Please sign in"));
		request.setAttribute(CURRENT_USER, user);
		return true;
	}

	static Optional<String> bearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith("Bearer ")) {
			return Optional.empty();
		}
		return Optional.of(header.substring("Bearer ".length()).trim());
	}
}
