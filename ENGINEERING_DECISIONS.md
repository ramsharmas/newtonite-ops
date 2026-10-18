# Engineering decisions

Why the app is built the way it is, and what each choice costs.

## 1. Keep the scope small

There is one record type, the **work item**. Comments and history belong to it. A
refund, an incident and a document check are all work items with a different `kind`,
priority and approval flag. Roles are only **member** and **lead**, per team.

The time went into what goes wrong when many people use the same items at once, not into
more screens or more record types.

## 2. Stack

| Choice | Why | Trade-off |
|---|---|---|
| Java 21, Spring Boot 4.1 | Well known, good transaction support, easy for reviewers to read. Boot 4 because 3.x is out of open-source support. | Slower to start than a small Node app (about 25 seconds on my laptop). |
| H2 database in a file (`./data`) | Nothing to install. It has real transactions and row locks, which the concurrency rules rely on. | Single process only. Moving to Postgres means changing the JDBC URL and driver; the queries are plain JPQL. |
| Spring Data JPA (Hibernate) | `@Version` gives optimistic locking for free, and `@Transactional` keeps each change in one transaction. | Hibernate creates tables on start (`ddl-auto=update`). Fine for a demo; production would use Flyway. |
| Plain HTML, CSS and JavaScript | No build step and nothing to install. It only uses the JSON API, so it doesn't care what language the server is in. | No components or type checking; acceptable for one page. |

## 3. Two people claim the same item

**Problem:** Ben and Chen both click "Take ownership" at the same moment. Reading the
item, seeing nobody owns it and then saving would let both "win", and the second save
would silently replace the first.

**Decision:** claiming is a single conditional UPDATE
(`WorkItemRepository.claim`):

```sql
update work_items set assignee_id = :me, status = 'IN_PROGRESS', version = version + 1
 where id = :id and assignee_id is null and status = 'OPEN'
```

The database locks the row, so the second UPDATE waits for the first, then finds
`assignee_id` is no longer null and changes 0 rows. The service sees 0 and answers
409 "Already taken by Ben Okafor".

**Tested by:** `ClaimTest.sixClaimsAtTheSameMomentExactlyOneWins` sends six claims at once.

## 4. Two people edit the same item

**Problem:** Asha and Farah open the same item. Farah saves first. If Asha then saves,
her old copy would overwrite Farah's change without anyone noticing.

**Decision:** optimistic locking. Every item has a `version` that goes up by one on
every change (`@Version` in `WorkItem`). The page sends the version it started from.

- If it doesn't match the current version, the server answers 409 `conflict` and includes
  the item as it is now.
- If two saves pass that check at the same moment, Hibernate's `where version = ?` on the
  UPDATE stops the second one, and the controller turns that into the same 409.

The page then shows both versions and offers **Apply my edits on top**. That re-sends
**only the fields this person changed**, against the new version. This matters: re-sending
the whole form would quietly undo the other person's change (for example put their new
priority back to the old one).

Comments don't change the item, so they don't bump the version. Otherwise every comment
would cause edit conflicts.

**Why not lock the item while someone edits?** Locks get left behind when people close
their laptop or lose their connection, and then need timeouts and "break lock" buttons.
Real conflicts are rare, so detecting them when they happen is simpler and never blocks
anyone.

**Tested by:** `ItemEditingTest.saveBasedOnAnOldVersionIsRefusedAndReturnsTheCurrentItem`
and `twoSavesAtTheSameMomentOnlyOneWins`.

## 5. Double clicks and retries

**Problem:** a double click, or a retry after a timeout, could create the same item or
comment twice.

**Decision:** the page sends an `Idempotency-Key` header (a random id made when the form
opens) with every create and comment. `Idempotency` saves the response together with
the change, in the same transaction. A repeat with the same key gets the saved response
back (`Idempotent-Replayed: true`) and nothing new is created. A unique constraint on
(user, key) handles the case where both copies arrive at the same moment: the second
insert fails, its transaction rolls back, and it answers with the first one's result.
Reusing a key for a different request gives 422.

Claims, edits and status changes don't need keys. Repeating them is already safe because
of the checks in sections 3 and 4.

**Tested by:** `IdempotencyTest`, including two identical requests sent at once.

## 6. Who can do what

- **Team access.** Every read and write first checks that the person is in the item's team
  (`ItemService.visible`). Outsiders get 404, the same as for an item that doesn't exist,
  so they can't even learn which ids exist. Search always filters by the person's teams
  in SQL. This is enforced on the server; the page hiding things is only a convenience.
- **Workflow rules live in one table** (`Workflow.java`): for each action, the statuses
  it is allowed from and who may do it (any member, the owner or a lead, or only a lead).
  The API uses it to refuse actions. The item page uses the same check to grey out buttons
  and show the reason, so the two never disagree.
- **Approval.** Items marked "needs approval" can't be resolved until a lead approves.
  Nobody can approve their own work: not the person who raised it, and not the owner.

**Tested by:** `AccessTest` and `ApprovalTest`.

## 7. History

Every change writes a row to `item_events` **in the same transaction** as the change. If
the change fails, the history row is rolled back too, so there is never a change without
its history or the other way round. Edits record which fields changed, from what, to what.

## 8. Notifications run in the background

**Problem:** sending notifications (today in-app; email or Slack later) can be slow or
fail. That must not slow down or break the change itself, and a notification must not be
lost or sent twice.

**Decision:** the "outbox" pattern.

1. The change saves a `jobs` row in its own transaction. So a job exists if and only if
   the change was committed.
2. `JobWorker` runs every second, picks up due jobs and runs each in its own transaction.
3. If a job fails, it is retried later with a growing wait (2s, 4s, 8s, 16s). After 5
   failures it is marked `DEAD` and kept with its last error for someone to look at.
4. A unique constraint on (job, person) in `notifications` means a job that runs twice
   (a retry, or two workers) still gives each person one notification.

Who gets notified: the person who raised the item and its owner, plus the team's leads
for new items and the previous owner when an item is released. Never the person who made
the change.

**Tested by:** `NotificationJobTest`, which makes delivery fail on purpose.

## 9. Search and sorting

Search is case-insensitive text matching on title and description, always limited to the
person's teams and to 100 results. Lists can be sorted by priority (stored as a number
next to the label so it sorts correctly), newest, or recently updated. There are indexes
on (team, status) and owner. This is fine for thousands of items; for much more I'd use
Postgres full-text search.

## 10. Keeping the page up to date

The page checks for changes every 5 seconds. If the open item changed and the person
isn't typing, it reloads it. If they are typing, it shows a warning instead of throwing
their edits away. Live push (server-sent events) would be nicer, but polling is simple
and good enough for a team-sized app.

## 11. Testing approach

The tests start the real app on a random port with an empty in-memory database and call
it over HTTP, exactly like the browser. The concurrency tests really send requests at
the same moment from several threads, so they test the database behaviour, not a mock of
it. The background worker is switched off in tests and run by hand, so the tests decide
when jobs run and can simulate time passing.
