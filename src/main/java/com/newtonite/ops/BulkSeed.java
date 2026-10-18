package com.newtonite.ops;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional: generates a lot of extra work items so the app can be tried (and measured) at
 * the size the brief talks about. Off unless {@code newtonite.seed-bulk-items} is above 0:
 *
 * <pre>./mvnw spring-boot:run -Dspring-boot.run.arguments=--newtonite.seed-bulk-items=20000</pre>
 *
 * Uses plain batched SQL because going through the service layer for tens of thousands of
 * rows would take minutes. The data is random but repeatable (fixed seed), and every item
 * gets the history rows the app expects.
 */
@Component
@Order(2)
class BulkSeed implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BulkSeed.class);

	private static final String[] WHAT = { "Refund request", "Chargeback dispute", "Settlement mismatch", "KYC review",
			"Webhook failures", "Login problem", "Access request", "Data export request", "Payout delayed",
			"Duplicate charge", "Invoice correction", "Fraud alert" };
	private static final String[] WHO = { "Hartley & Sons", "Brightside Ltd", "Northwind", "Acme Retail", "Lumen Foods",
			"Pioneer Travel", "Orbit Fitness", "Kestrel Labs", "Maple & Co", "Blue Harbor", "Quartz Studio", "Redwood Bank" };

	private final JdbcTemplate jdbc;
	private final int wanted;

	BulkSeed(JdbcTemplate jdbc, @Value("${newtonite.seed-bulk-items:0}") int wanted) {
		this.jdbc = jdbc;
		this.wanted = wanted;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (wanted <= 0) {
			return;
		}
		long existing = jdbc.queryForObject("select count(*) from work_items", Long.class);
		if (existing >= wanted) {
			return;
		}
		int toCreate = (int) (wanted - existing);

		Map<Long, List<Long>> members = new HashMap<>();
		Map<Long, List<Long>> leads = new HashMap<>();
		jdbc.query("select team_id, user_id, role from memberships", rs -> {
			long team = rs.getLong("team_id");
			long user = rs.getLong("user_id");
			members.computeIfAbsent(team, k -> new ArrayList<>()).add(user);
			if ("LEAD".equals(rs.getString("role"))) {
				leads.computeIfAbsent(team, k -> new ArrayList<>()).add(user);
			}
		});
		if (members.isEmpty()) {
			log.warn("Not generating bulk items: there are no teams yet (is newtonite.seed-demo-data off?)");
			return;
		}

		long started = System.currentTimeMillis();
		Long maxIdBefore = jdbc.queryForObject("select coalesce(max(id), 0) from work_items", Long.class);
		List<Long> teamIds = List.copyOf(members.keySet());
		Random random = new Random(7);
		Instant now = Instant.now();

		List<Object[]> rows = new ArrayList<>(1000);
		for (int i = 0; i < toCreate; i++) {
			long team = teamIds.get(random.nextInt(teamIds.size()));
			List<Long> people = members.get(team);

			String priority = pick(random, new String[] { "URGENT", "HIGH", "MEDIUM", "LOW" }, new double[] { .05, .20, .50, .25 });
			String status = pick(random, new String[] { "OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED" },
					new double[] { .30, .30, .12, .20, .08 });
			Long assignee = status.equals("OPEN") ? null : people.get(random.nextInt(people.size()));
			long creator = people.get(random.nextInt(people.size()));

			boolean requiresApproval = random.nextDouble() < .2;
			Long approvedBy = null;
			if (requiresApproval && !status.equals("OPEN") && !status.equals("CANCELLED")) {
				for (long lead : leads.getOrDefault(team, List.of())) {
					if (lead != creator && (assignee == null || lead != assignee)) {
						approvedBy = lead;
						break;
					}
				}
				if (approvedBy == null) {
					requiresApproval = false;
				}
			}

			// Created up to 60 days ago; last activity somewhere between then and now, leaning recent.
			long ageMinutes = Duration.ofDays(60).toMinutes();
			Instant created = now.minus(Duration.ofMinutes((long) (random.nextDouble() * ageMinutes)));
			long sinceCreated = Duration.between(created, now).toMinutes();
			Instant updated = created.plus(Duration.ofMinutes((long) (sinceCreated * Math.sqrt(random.nextDouble()))));

			String what = WHAT[random.nextInt(WHAT.length)];
			String who = WHO[random.nextInt(WHO.length)];
			String title = what + " - " + who + " #" + (1000 + i);
			String description = "Raised for " + who + ". " + what.toLowerCase() + " that needs a person to look at it and decide what to do next.";

			rows.add(new Object[] { team, title, description, random.nextDouble() < .1 ? "incident" : "task", status,
					priority, rank(priority), assignee, creator, requiresApproval, approvedBy, 0L,
					Timestamp.from(created), Timestamp.from(updated) });
			if (rows.size() == 1000) {
				insert(rows);
			}
		}
		insert(rows);

		// History rows the item page expects: who created it, and who took it.
		jdbc.update("insert into item_events (item_id, actor_id, type, detail, created_at) "
				+ "select id, created_by, 'created', '{}', created_at from work_items where id > ?", maxIdBefore);
		jdbc.update("insert into item_events (item_id, actor_id, type, detail, created_at) "
				+ "select id, assignee_id, 'claimed', '{}', created_at from work_items where id > ? and assignee_id is not null",
				maxIdBefore);

		log.info("Generated {} extra work items in {} ms", toCreate, System.currentTimeMillis() - started);
	}

	private void insert(List<Object[]> rows) {
		if (rows.isEmpty()) {
			return;
		}
		jdbc.batchUpdate("insert into work_items (team_id, title, description, kind, status, priority, priority_rank, "
				+ "assignee_id, created_by, requires_approval, approved_by, version, created_at, updated_at) "
				+ "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
		rows.clear();
	}

	private static int rank(String priority) {
		return switch (priority) {
			case "URGENT" -> 0;
			case "HIGH" -> 1;
			case "MEDIUM" -> 2;
			default -> 3;
		};
	}

	private static String pick(Random random, String[] values, double[] weights) {
		double roll = random.nextDouble();
		double total = 0;
		for (int i = 0; i < values.length; i++) {
			total += weights[i];
			if (roll < total) {
				return values[i];
			}
		}
		return values[values.length - 1];
	}
}
