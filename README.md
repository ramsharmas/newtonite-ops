# Newtonite Ops

A shared work queue for operations teams. People in a team pick up work items (a refund,
an incident, a document check), edit them, comment, and move them through
open → in progress → resolved → closed. Some items need a lead's approval first.

The interesting part is what happens when several people work on the same item at the
same time. See [ENGINEERING_DECISIONS.md](ENGINEERING_DECISIONS.md) for how each case is
handled and why.

Built with Java 21, Spring Boot 4.1, Spring Data JPA (Hibernate) and an H2 database
stored in a local file. The front end is plain HTML, CSS and JavaScript with no build step.

## Run it

You need Java 21 or newer (`java -version`). Nothing else: the Maven wrapper downloads
Maven and the dependencies the first time (this needs internet once).

```bash
./mvnw spring-boot:run
```

Then open http://localhost:8080 and pick a person to sign in. The first start takes a
little longer while dependencies download.

Other useful commands:

```bash
./mvnw test                          # run the 30 automated tests
rm -rf data && ./mvnw spring-boot:run   # start again with fresh demo data
PORT=9090 ./mvnw spring-boot:run     # use another port if 8080 is busy
```

Stop the app with Ctrl+C.

### In VS Code

Open this folder in VS Code and install the **Extension Pack for Java** when it asks.
Then open `src/main/java/com/newtonite/ops/NewtoniteOpsApplication.java` and click
**Run** above `main`, or run `./mvnw spring-boot:run` in the terminal.

## Demo data

On first start the app creates 6 people, 4 teams and 5 work items. Roles are per team:

| Team       | Leads         | Members     |
|------------|---------------|-------------|
| Payments   | Asha, Farah   | Ben, Chen   |
| Support    | Divya         | Asha, Ben   |
| Platform   | Erik          | Chen        |
| Compliance | Farah         | Divya       |

## Two-minute demo

Each browser tab keeps its own sign-in, so use two tabs to be two people.

1. **Two people claim the same item.** Tab 1: Ben. Tab 2: Chen. Both open
   "Customer charged twice for one order" and click **Take ownership**. One gets it; the
   other is told "Already taken by ...".
2. **Two people edit the same item.** Tab 1: Asha. Tab 2: Farah. Both open
   "Settlement total doesn't match the bank". Farah changes the priority to Low and
   saves. Asha changes the title and saves. Asha's save is refused and she sees both
   versions side by side. **Apply my edits on top** keeps Farah's priority and adds
   Asha's title. The history shows both changes.
3. **Approval.** The refund item from step 1 needs approval, so its new owner can't
   resolve it yet (try it). Asha, a Payments lead who didn't raise it and doesn't own it,
   clicks **Approve**. Now the owner can resolve it, and a lead (Asha or Farah) can close
   it. A lead can't approve their own work.
4. **Team access.** Sign in as Erik (Platform only). He can't see or search any Payments
   items. The server enforces this; it is not just hidden in the page.
5. **Notifications.** Sign in as Chen, who raised the refund item. The bell shows what
   others did to it. These are sent by a background worker that retries on failure.

## Tests

`./mvnw test` starts the real app on a random port with an in-memory database and calls
the API over HTTP, the same way the browser does. They cover:

- sign-in, and that people outside a team get "not found" for its items and searches
- creating, searching, sorting, editing and commenting
- edit conflicts, including two saves sent at the same moment (exactly one wins)
- six claims sent at the same moment (exactly one wins)
- approval rules and who may release, close and cancel
- repeated requests with the same Idempotency-Key, including two at once
- notification jobs: delivered to the right people, never twice, retried after a
  failure, and marked dead after 5 failures

## API

All endpoints except the first two need `Authorization: Bearer <token>` from `/api/login`.

| Method and path                         | What it does                                          |
|-----------------------------------------|-------------------------------------------------------|
| `GET /api/users`                        | People to pick from on the sign-in screen             |
| `POST /api/login` `{userId}`            | Signs in, returns a token                             |
| `POST /api/logout`                      | Signs out                                             |
| `GET /api/me`                           | Who I am and my teams                                 |
| `GET /api/items`                        | My teams' items. Optional: `q`, `teamId`, `status` (`active` = open or in progress), `assigned` (`me`, `unassigned`), `sort` (`updated`, `priority`, `created`) |
| `POST /api/items`                       | New item. Send an `Idempotency-Key` header            |
| `GET /api/items/{id}`                   | Item with comments, history and the actions I can take |
| `PATCH /api/items/{id}` `{version, ...}` | Edit title, description or priority. 409 if `version` is out of date |
| `POST /api/items/{id}/claim`            | Take ownership. 409 if someone else has it            |
| `POST /api/items/{id}/actions/{action}` | `release`, `approve`, `resolve`, `close`, `reopen`, `cancel` |
| `POST /api/items/{id}/comments`         | Add a comment. Send an `Idempotency-Key` header       |
| `GET /api/notifications`                | My latest notifications and unread count              |
| `POST /api/notifications/read`          | Mark all as read                                      |

Errors look like `{"error": "already_claimed", "message": "Already taken by Ben Okafor"}`.

## Code layout

```
src/main/java/com/newtonite/ops/
  auth/    people, teams, roles, demo sign-in, the check that runs on every request
  items/   work items, the workflow rules (Workflow.java), search, history, the API
  jobs/    background jobs, the worker, notifications
  web/     error responses and Idempotency-Key handling
  DemoData.java   the starting data
src/main/resources/static/   the web page (index.html, app.js, styles.css)
src/test/java/               the tests
```

## Known limitations

- Sign-in is a demo picker with no passwords. Real login (SSO) would replace
  `AuthController` and `AuthInterceptor`; nothing else depends on how people sign in.
- The page checks for changes every 5 seconds instead of getting live updates.
- Search uses simple text matching, which is fine for thousands of items but not millions.
- No file attachments, no admin screen, and no way to retry a dead job from the UI
  (they stay in the `jobs` table with the last error).
- Hibernate creates the tables on start (`ddl-auto=update`). A real deployment would use
  versioned migrations (Flyway).

## What I'd do next

1. Real sign-in through the company's identity provider.
2. Move to Postgres and add Flyway migrations.
3. Push updates to the page (server-sent events) instead of polling.
4. Due dates and alerts when an urgent item sits unowned for too long.
5. A small admin page to inspect and retry failed jobs.
