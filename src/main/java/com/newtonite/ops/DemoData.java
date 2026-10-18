package com.newtonite.ops;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
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
 * and 12 work items in different states (unowned, in progress, waiting for approval,
 * resolved, closed, and a few that nobody has touched for days). Runs once; restarting
 * keeps whatever is in the database.
 *
 * Who is where (useful for the demo):
 *   Payments   - Asha (lead), Farah (lead), Ben, Chen
 *   Support    - Divya (lead), Asha, Ben
 *   Platform   - Erik (lead), Chen
 *   Compliance - Farah (lead), Divya
 */
@Component
@Order(1)
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

		// ---- Payments ----

		// Unowned, urgent, and waiting for a lead's approval: the first thing a lead should see.
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
		comment(settlement, asha, hoursAgo(now, 6),
				"Bank confirmed the duplicates. Go ahead and correct the report, then send me the diff.");

		// Owned but silent for almost three days: shows up under "No activity".
		WorkItem payout = item(payments, ben, hoursAgo(now, 74), "Merchant payout stuck in \"processing\" for 3 days",
				"Merchant Hartley & Sons says payout #P-2291 has not arrived. Our dashboard still shows it as processing.",
				WorkItem.KIND_TASK, Priority.HIGH, false);
		claim(payout, chen, hoursAgo(now, 72));

		WorkItem chargeback = item(payments, chen, hoursAgo(now, 30), "Chargeback evidence due Friday for order #47790",
				"The card network wants the delivery confirmation and the customer's chat transcript.",
				WorkItem.KIND_TASK, Priority.MEDIUM, false);
		claim(chargeback, ben, hoursAgo(now, 28));
		comment(chargeback, ben, hoursAgo(now, 4), "Evidence pack uploaded to the dispute portal.");
		resolve(chargeback, ben, hoursAgo(now, 3));

		WorkItem vat = item(payments, asha, hoursAgo(now, 24 * 6), "Reconcile Q3 VAT export",
				"Finance needs the VAT export to match the ledger before the filing date.",
				WorkItem.KIND_TASK, Priority.LOW, false);
		claim(vat, ben, hoursAgo(now, 24 * 6 - 2));
		resolve(vat, ben, hoursAgo(now, 24 * 5));
		close(vat, asha, hoursAgo(now, 24 * 4));

		// ---- Support ----

		item(support, divya, hoursAgo(now, 5), "Customer can't log in after password reset",
				"The reset email arrives, but the new password is rejected. Only happens in the mobile app.",
				WorkItem.KIND_TASK, Priority.MEDIUM, false);

		WorkItem renewal = item(support, ben, hoursAgo(now, 10), "Refund request: duplicate subscription renewal",
				"Customer was renewed twice on the 3rd. Needs a refund of the second charge.",
				WorkItem.KIND_TASK, Priority.MEDIUM, true);
		claim(renewal, asha, hoursAgo(now, 9));
		approve(renewal, divya, hoursAgo(now, 8));

		item(support, asha, hoursAgo(now, 24 * 4), "Update help-center wording for failed payments",
				"The article still mentions the old retry schedule.",
				WorkItem.KIND_TASK, Priority.LOW, false);

		// ---- Platform ----

		WorkItem webhooks = item(platform, erik, hoursAgo(now, 3), "Webhooks failing after Tuesday's deploy",
				"About 8% of merchant webhooks return 500 since release 2.14. Retries are piling up in the queue.",
				WorkItem.KIND_INCIDENT, Priority.HIGH, false);
		claim(webhooks, chen, hoursAgo(now, 2));

		item(platform, erik, hoursAgo(now, 1), "API gateway TLS certificate expires in 6 days",
				"Renewal failed on the last two attempts. If it lapses, every merchant integration breaks.",
				WorkItem.KIND_INCIDENT, Priority.URGENT, false);

		// ---- Compliance ----

		item(compliance, divya, hoursAgo(now, 30), "KYC document review for Brightside Ltd",
				"New merchant uploaded ID and proof of address. Needs checking before the account is switched on.",
				WorkItem.KIND_TASK, Priority.MEDIUM, true);

		WorkItem gdpr = item(compliance, divya, hoursAgo(now, 5), "GDPR data export request #772",
				"A customer asked for a copy of everything we hold about them. Legal deadline is 30 days.",
				WorkItem.KIND_TASK, Priority.HIGH, true);
		claim(gdpr, divya, hoursAgo(now, 4));
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

	private void approve(WorkItem item, User lead, Instant at) {
		item.setApprovedBy(lead.getId());
		item.touch(at);
		events.save(new ItemEvent(item.getId(), lead.getId(), "approved", "{}", at));
	}

	private void resolve(WorkItem item, User user, Instant at) {
		item.setStatus(Status.RESOLVED);
		item.touch(at);
		events.save(new ItemEvent(item.getId(), user.getId(), "resolved", "{}", at));
	}

	private void close(WorkItem item, User lead, Instant at) {
		item.setStatus(Status.CLOSED);
		item.touch(at);
		events.save(new ItemEvent(item.getId(), lead.getId(), "closed", "{}", at));
	}

	private void comment(WorkItem item, User author, Instant at, String body) {
		Comment comment = comments.save(new Comment(item.getId(), author.getId(), body, at));
		item.touch(at);
		events.save(new ItemEvent(item.getId(), author.getId(), "commented", "{\"commentId\":" + comment.getId() + "}", at));
	}

	private static Instant hoursAgo(Instant now, int hours) {
		return now.minus(Duration.ofHours(hours));
	}
}
