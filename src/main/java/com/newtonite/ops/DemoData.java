package com.newtonite.ops;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.newtonite.ops.auth.Membership;
import com.newtonite.ops.auth.MembershipRepository;
import com.newtonite.ops.auth.Role;
import com.newtonite.ops.auth.Team;
import com.newtonite.ops.auth.TeamRepository;
import com.newtonite.ops.auth.User;
import com.newtonite.ops.auth.UserRepository;
import com.newtonite.ops.items.Comment;
import com.newtonite.ops.items.CommentRepository;
import com.newtonite.ops.items.ItemEvent;
import com.newtonite.ops.items.ItemEventRepository;
import com.newtonite.ops.items.Priority;
import com.newtonite.ops.items.Status;
import com.newtonite.ops.items.WorkItem;
import com.newtonite.ops.items.WorkItemRepository;

/**
 * Fills an empty database with a small, believable starting point: 6 people, 4 teams
 * and 5 work items. Runs once; restarting keeps whatever is in the database.
 *
 * Who is where (useful for the demo):
 *   Payments   - Asha (lead), Farah (lead), Ben, Chen
 *   Support    - Divya (lead), Asha, Ben
 *   Platform   - Erik (lead), Chen
 *   Compliance - Farah (lead), Divya
 */
@Component
@ConditionalOnProperty(name = "newtonite.seed-demo-data", havingValue = "true", matchIfMissing = true)
class DemoData implements ApplicationRunner {

	private final UserRepository users;
	private final TeamRepository teams;
	private final MembershipRepository memberships;
	private final WorkItemRepository items;
	private final CommentRepository comments;
	private final ItemEventRepository events;

	DemoData(UserRepository users, TeamRepository teams, MembershipRepository memberships, WorkItemRepository items,
			CommentRepository comments, ItemEventRepository events) {
		this.users = users;
		this.teams = teams;
		this.memberships = memberships;
		this.items = items;
		this.comments = comments;
		this.events = events;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (users.count() > 0) {
			return;
		}

		User asha = users.save(new User("Asha Rao"));
		User ben = users.save(new User("Ben Okafor"));
		User chen = users.save(new User("Chen Wei"));
		User divya = users.save(new User("Divya Nair"));
		User erik = users.save(new User("Erik Larsen"));
		User farah = users.save(new User("Farah Haddad"));

		Team payments = teams.save(new Team("Payments"));
		Team support = teams.save(new Team("Support"));
		Team platform = teams.save(new Team("Platform"));
		Team compliance = teams.save(new Team("Compliance"));

		join(asha, payments, Role.LEAD);
		join(farah, payments, Role.LEAD);
		join(ben, payments, Role.MEMBER);
		join(chen, payments, Role.MEMBER);
		join(divya, support, Role.LEAD);
		join(asha, support, Role.MEMBER);
		join(ben, support, Role.MEMBER);
		join(erik, platform, Role.LEAD);
		join(chen, platform, Role.MEMBER);
		join(farah, compliance, Role.LEAD);
		join(divya, compliance, Role.MEMBER);

		Instant now = Instant.now();

		item(payments, chen, hoursAgo(now, 2), "Customer charged twice for one order",
				"Order #48213 shows two card captures of EUR 129.00 a minute apart. "
						+ "The customer wants the duplicate refunded. Refunds over EUR 100 need a lead's approval.",
				WorkItem.KIND_TASK, Priority.URGENT, true);

		WorkItem settlement = item(payments, asha, hoursAgo(now, 26), "Settlement total doesn't match the bank",
				"Yesterday's settlement report is EUR 312.40 lower than what arrived in the bank account.",
				WorkItem.KIND_TASK, Priority.HIGH, false);
		claim(settlement, ben, hoursAgo(now, 25));
		comment(settlement, ben, hoursAgo(now, 20),
				"Found three refunds that appear in both the 14th and the 15th files. Checking with the bank before I correct it.");

		item(support, divya, hoursAgo(now, 5), "Customer can't log in after password reset",
				"The reset email arrives, but the new password is rejected. Only happens in the mobile app.",
				WorkItem.KIND_TASK, Priority.MEDIUM, false);

		WorkItem webhooks = item(platform, erik, hoursAgo(now, 3), "Webhooks failing after Tuesday's deploy",
				"About 8% of merchant webhooks return 500 since release 2.14. Retries are piling up in the queue.",
				WorkItem.KIND_INCIDENT, Priority.HIGH, false);
		claim(webhooks, chen, hoursAgo(now, 2));

		item(compliance, divya, hoursAgo(now, 30), "KYC document review for Brightside Ltd",
				"New merchant uploaded ID and proof of address. Needs checking before the account is switched on.",
				WorkItem.KIND_TASK, Priority.MEDIUM, true);
	}

	private void join(User user, Team team, Role role) {
		memberships.save(new Membership(user.getId(), team.getId(), role));
	}

	private WorkItem item(Team team, User author, Instant at, String title, String description, String kind,
			Priority priority, boolean requiresApproval) {
		WorkItem item = items.save(new WorkItem(team.getId(), title, description, kind, priority, requiresApproval,
				author.getId(), at));
		events.save(new ItemEvent(item.getId(), author.getId(), "created", "{}", at));
		return item;
	}

	private void claim(WorkItem item, User user, Instant at) {
		item.setAssigneeId(user.getId());
		item.setStatus(Status.IN_PROGRESS);
		item.touch(at);
		events.save(new ItemEvent(item.getId(), user.getId(), "claimed", "{}", at));
	}

	private void comment(WorkItem item, User author, Instant at, String body) {
		Comment comment = comments.save(new Comment(item.getId(), author.getId(), body, at));
		events.save(new ItemEvent(item.getId(), author.getId(), "commented", "{\"commentId\":" + comment.getId() + "}", at));
	}

	private static Instant hoursAgo(Instant now, int hours) {
		return now.minus(Duration.ofHours(hours));
	}
}
