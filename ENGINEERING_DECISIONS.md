# Engineering decisions

The brief describes a company whose problem is *coordination under pressure*: people step on
each other, work gets lost, nobody can say why something happened. So I spent the day on
making a small system **correct when several people use it at once** and **easy to read at a
glance**, not on a long feature list. There is one record type (the work item), two roles
(lead and member, per team) and seven workflow actions.

This file explains the five decisions I would defend first, then the smaller ones, what I left
out on purpose, how the system behaves when things fail, and what I'd change if it grew.

---

## The five decisions that matter most

### 1. Concurrency: a different tool for each kind of conflict

People collide in three different ways, and one mechanism does not fit all of them.

**Two people take the same item** is a *race for a single slot*. Reading the item, seeing no
owner and then saving would let both win. So claiming is one conditional statement
(`WorkItemRepository.claim`):

```sql
update work_items set assignee_id = :me, status = 'IN_PROGRESS', version = version + 1
 where id = :id and assignee_id is null and status = 'OPEN'
```

The database serializes the two updates; the second finds the owner set and changes 0 rows.
The service turns that into `409 "Already taken by Ben Okafor"`. Tested with six claims in the
same instant (`ClaimTest`): one 200, five 409s, one `claimed` row in the history.

**Two people edit the same item** is *lost update*: Farah saves, then Asha saves her older
copy over it. Every item has a `version` (JPA `@Version`) that goes up on every change, and
the page sends the version it started from. A stale save gets `409 conflict` carrying the
current item, and Hibernate's `where version = ?` catches two saves that pass the check in the
same instant. The page then shows **both versions side by side** and offers *Apply my edits
on top*, which re-sends **only the fields that person changed** against the new version. (Sending
the whole form would quietly undo the other person's priority change.)

I chose this over locking the item while someone edits. Locks get left behind when a laptop
closes, and then you need timeouts and a "break lock" button. Real conflicts are rare;
detecting them when they happen is simpler and never blocks anyone.

**Two status changes at once** (the owner resolves while a lead cancels; two leads approve)
need no extra mechanism: they go through the same versioned save, so exactly one takes
effect, and the loser is told why. `WorkflowRaceTest` checks that exactly one wins and the
history records one action; `e2e/soak_test.py` checks the same rule under random load
(*the version always equals the number of changes in the history*).

A detail that matters: **comments don't bump `version`**, otherwise every comment would
cause an edit conflict for whoever is typing a description. They do count as *activity*
(`updatedAt`), so an item people are discussing doesn't look forgotten.

### 2. Retries and double clicks: idempotency keys

A double click, or a retry after a timeout the person didn't see the result of, must not
create two items or two comments. The page sends an `Idempotency-Key` (a random id made when
the dialog opens, or when a comment is written) and the server stores the response **in the
same transaction as the change**. A repeat gets the saved response back
(`Idempotent-Replayed: true`). A unique constraint on (user, key) settles the case where two
copies arrive at the same moment: the second insert fails, its transaction rolls back and it
answers with the winner's result. Reusing a key for a different request is `422`.

Claims, edits and status changes don't need keys: repeating them is already safe because of
decision 1. I only added keys where a repeat would *create* something.
Tested in `IdempotencyTest` (including two identical requests at once), and in the browser: a
comment sent while offline is shown as "Not sent", and **Retry** delivers it exactly once
because the retry reuses the key.

### 3. Authorization lives on the server, at the resource, in one table

- **Team access.** Every read and write first resolves the person's role *in the item's team*
  (`ItemService.visible`). Outsiders get `404`, the same answer as for an item that doesn't
  exist, so ids can't be probed. List, search, counts and history are always filtered by the
  person's teams in SQL, never "fetch then filter".
- **Workflow rules are data, not scattered `if`s.** `Workflow.java` is one table: for each
  action, the statuses it is allowed from and who may do it (any member, owner or lead, only a
  lead). The API refuses with it, **and the item page uses the same answer to hide, grey out
  and explain buttons**. The server returns the allowed actions with the item, so the page
  never has to re-implement the rules and the two can't disagree.
- **Four-eyes on approval.** An item marked "needs approval" can't be resolved until a lead
  approves, and nobody can approve their own work: not the person who raised it, not its owner.

Trade-off: two roles are coarse. Adding a role means adding a row to the table in one file,
which was the point of putting the rules there.

### 4. Background work: a transactional outbox, built to run twice

Notifications can be slow or fail, and must neither slow down a change nor get lost or sent
twice. The change and a `jobs` row are saved in **one transaction**, so a job exists if and only
if the change was committed. `JobWorker` runs due jobs every second, each in its own
transaction; a failure is retried with growing waits (2, 4, 8, 16 s) and after 5 attempts the
job is kept as `DEAD` with its last error. A unique constraint on (job, person) makes
delivery idempotent, so a retry, or two workers running the same job, still gives each person
one notification. Recipients are decided when the change happens (the person who raised the item,
its owner, plus the team's leads for new work), never the person who made the change.

I deliberately did *not* add a message broker. Polling a table is enough here, keeps
"change committed" and "job queued" atomic for free, and is something I can explain end to end.
`NotificationJobTest` makes delivery fail on purpose to prove retry, backoff, dead-lettering and no duplicates.

### 5. Built to be paged: nothing loads "everything"

The brief expects tens of thousands of active items and a history that only grows. So:

- **Keyset cursors, not offsets.** A page is "the next 30 after the last row I showed you",
  with ties broken by id. The cost of page 250 equals the cost of page 1, and a row that
  changes while someone pages can't make others show twice. Tested for all three sort orders,
  including the worst case where every timestamp is identical, and for tampered cursors.
- **The cards are filters.** The numbers at the top ("needs an owner", "waiting for approval",
  "gone quiet") come from **one aggregate query built from the same predicates** as the list
  filters, so a card's number is always the length of the list it opens (`OverviewTest`).
- **An item's history is bounded too.** Opening an item returns the newest 50 history entries
  (plus their comments); older ones come on request. A long incident with thousands of comments
  stays cheap to open (`HistoryPagingTest`).
- **The page is paged as well.** It keeps only the pages the person scrolled through, and if
  data changes under a deep list it shows "New activity. Refresh the list" instead of moving rows
  under their cursor.

Measured with 50,000 items on a laptop: first page 17-26 ms, a page 249 pages deep 45-57 ms,
overview 75 ms. The weak spot is text search (`like '%x%'`, a scan: 160-280 ms), which is where a
real index comes first.

---

## Smaller decisions

**"What needs attention" is a product decision, not a filter.** The overview exists because the
brief says management can't tell what is forgotten or unowned. An item is "gone quiet" when it is
unfinished and nothing, including comments, has happened for 24 hours (configurable). Resolved
items stay in "active" until a lead closes them, so closing doesn't depend on someone remembering.
The item page also says in plain words what happens next ("Nobody owns this yet", "Waiting for a
team lead to approve", "You can approve this").

**History is written in the same transaction as the change.** Never a change without its row
or a row without its change. The soak test checks this invariant under load.

**Stack.** Java 21 and Spring Boot 4.1 for transactions, optimistic locking and readable code
(Boot 4 because 3.x is out of open-source support). H2 in a file so nothing needs installing, while
still giving real transactions and row locks, which the concurrency rules rely on. Plain
HTML/CSS/ES modules with no build step: it only uses the JSON API, so the server language doesn't
matter, and the page is split into small modules by screen rather than one script.

**The page treats the server as the truth.** It keeps a copy and refreshes it every 5 seconds;
the open item updates itself unless the person has unsaved edits, in which case it warns and keeps
their typing. Two actions are *optimistic*: taking ownership and commenting show their result at
once, then reconcile. The claim is rolled back and explained if someone else won; a comment that
can't be sent stays on screen as "Not sent" with Retry. Everything else waits for the server,
because showing a *wrong* approval or status would be worse than a 100 ms wait.
Polling over server-sent events: simple, stateless and good enough at team scale (limitation noted).

**Sign-in is a demo picker.** The point of the exercise is what happens after sign-in. Everything
asks "who is calling?" through one interceptor, so SSO would replace two classes.

**Tests follow the risks.** The dangerous behaviours are all "two things at once" and "who may
see this", so those are tested against the real app over HTTP with real threads. A mocked
repository can't prove that two UPDATEs serialize. A random concurrent soak test and 55
browser checks cover what example tests can't.

## What I chose not to build

Attachments, due dates/SLAs, moving items between teams, @-mentions, email/Slack delivery (the
outbox is where they'd plug in), an admin screen, and a message broker. Each is real work with
its own failure modes; I would rather ship a small system whose behaviour I can prove.

## How it behaves when things go wrong

| What fails | What happens |
|---|---|
| Two people act on the same item at once | Exactly one succeeds; the other gets a 409 that says why; the page corrects itself |
| A request is sent twice | Same response, nothing created twice |
| The notification job throws | Change is already saved; job retried at 2, 4, 8, 16 s; then `DEAD` with the error |
| The worker runs a job twice | Unique (job, person) means no duplicate notification |
| The network drops in the browser | Banner "Can't reach the server"; a comment stays as "Not sent" and can be retried safely |
| A sign-in expires (12 h) or the server forgets it | Back to sign-in with an explanation; nothing is half-saved |
| Someone opens a link to an item they can't see | "Item not found", the same as if it never existed |
| The database rejects a concurrent write | 409, never a 500 and never a lost update |

## If this grew by 10x

1. **Postgres** and Flyway; `SELECT ... FOR UPDATE SKIP LOCKED` in the job worker so many workers
   share the queue (the duplicate-safe delivery already tolerates two today).
2. **Full-text search** (Postgres `tsvector` or trigram) for text search, the first thing that
   would hurt.
3. **Server-sent events** replacing the 4-requests-per-5-seconds polling, with counts cached per
   team and invalidated on write; this is the largest load at thousands of simultaneous users.
4. Move the outbox to a log-based or broker-backed queue only when job volume needs it, keeping the
   "queued in the same transaction as the change" guarantee.
5. Partition or archive `item_events` by time; the item page only ever reads the newest rows and
   already pages the rest.
6. Real roles beyond lead/member, and audit export, driven from the same workflow table.
