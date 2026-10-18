#!/usr/bin/env python3
"""
Concurrency soak test: many people hammer the same few items at the same time with random
actions (claim, approve, resolve, close, reopen, cancel, edit with fresh and stale versions,
comment with repeated idempotency keys), then check the rules that must always hold.

It needs no libraries, only Python 3 and a running app with the demo data:

    ./mvnw spring-boot:run
    python3 e2e/soak_test.py http://localhost:8080 --seconds 45

What must hold afterwards, for every item:
  - the server never answered with a 5xx
  - the version equals the number of changes in the history (one history row per change, no
    change without a row, no row without a change; comments don't count as changes)
  - an in-progress item has an owner, and an open one has none
  - an item that needs approval is never resolved or closed without one, and is approved once
  - the history starts with "created" and has no duplicate entries
Exit code 0 = everything held.
"""
import argparse
import collections
import json
import random
import sys
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("base")
parser.add_argument("--seconds", type=int, default=30)
parser.add_argument("--threads", type=int, default=10)
args = parser.parse_args()
BASE = args.base.rstrip("/")


def call(method, path, token=None, body=None, key=None):
    request = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    if key:
        request.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw[:200].decode(errors="replace")}


people = call("GET", "/api/users")[1]
tokens = {p["name"]: call("POST", "/api/login", body={"userId": p["id"]})[1]["token"] for p in people}
names = list(tokens)
teams = {p["name"]: [t["id"] for t in p["teams"]] for p in people}
rolls = random.Random(1)

pool = []
for i in range(12):
    who = rolls.choice(names)
    status, item = call("POST", "/api/items", tokens[who], {
        "teamId": rolls.choice(teams[who]), "title": f"soak {i}", "priority": rolls.choice(["urgent", "high", "medium", "low"]),
        "requiresApproval": rolls.random() < .5})
    assert status == 201, (status, item)
    pool.append(item["id"])

stats = collections.Counter()
server_errors = []
lock = threading.Lock()
deadline = time.time() + args.seconds
ACTIONS = ["release", "approve", "resolve", "close", "reopen", "cancel"]


def worker(seed):
    rnd = random.Random(seed)
    while time.time() < deadline:
        who, item = rnd.choice(names), rnd.choice(pool)
        token, roll = tokens[who], rnd.random()
        if roll < .30:
            status, body = call("POST", f"/api/items/{item}/claim", token)
        elif roll < .62:
            status, body = call("POST", f"/api/items/{item}/actions/{rnd.choice(ACTIONS)}", token)
        elif roll < .80:
            status, body = call("GET", f"/api/items/{item}", token)
            if status == 200:
                version = max(0, body["item"]["version"] - (1 if rnd.random() < .3 else 0))  # sometimes out of date
                status, body = call("PATCH", f"/api/items/{item}", token, {
                    "version": version, "priority": rnd.choice(["urgent", "high", "medium", "low"]),
                    **({"title": f"soak {rnd.randint(0, 999)}"} if rnd.random() < .5 else {})})
        elif roll < .92:
            status, body = call("POST", f"/api/items/{item}/comments", token, {"body": "c"}, key=f"k{rnd.randint(0, 50)}")
        else:
            status, body = call("GET", "/api/items?limit=10", token)
        with lock:
            stats[status] += 1
            if status >= 500 or status == 0:
                server_errors.append((status, body))


threads = [threading.Thread(target=worker, args=(i,)) for i in range(args.threads)]
for t in threads:
    t.start()
for t in threads:
    t.join()

print(f"{sum(stats.values()):,} requests from {args.threads} threads in {args.seconds}s")
print("status codes:", dict(sorted(stats.items())), "(409 and 404 are the rules saying no; that is expected)")
print("server errors:", len(server_errors), server_errors[:3])

problems = []
for item in pool:
    reader = next(n for n in names if call("GET", f"/api/items/{item}", tokens[n])[0] == 200)
    detail = call("GET", f"/api/items/{item}", tokens[reader])[1]
    events = list(detail["history"])
    more = detail["hasOlderHistory"]
    while more:  # the item page only returns the newest part of the history, so page back through it
        page = call("GET", f"/api/items/{item}/history?before={events[0]['id']}&limit=100", tokens[reader])[1]
        events = page["history"] + events
        more = page["hasOlderHistory"]
    kinds = [e["type"] for e in events]
    current = detail["item"]
    changes = [k for k in kinds if k not in ("created", "commented")]
    if current["version"] != len(changes):
        problems.append((item, "version does not match the number of changes", current["version"], len(changes)))
    if current["status"] == "in_progress" and not current["assignee"]:
        problems.append((item, "in progress without an owner"))
    if current["status"] == "open" and current["assignee"]:
        problems.append((item, "open but owned"))
    if current["status"] in ("resolved", "closed") and current["requiresApproval"] and not current["approvedBy"]:
        problems.append((item, "finished without the approval it needs"))
    if kinds.count("approved") > 1:
        problems.append((item, "approved more than once"))
    if kinds[0] != "created":
        problems.append((item, "history does not start with 'created'"))
    if len({e["id"] for e in events}) != len(events):
        problems.append((item, "duplicate history entries"))

print("broken rules:", len(problems))
for problem in problems[:10]:
    print("  ", problem)
sys.exit(1 if server_errors or problems else 0)
