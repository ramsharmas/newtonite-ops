#!/usr/bin/env python3
"""
Browser checks for the behaviours that only exist in the page: optimistic "take ownership"
that gets corrected by the server, comments that survive a dropped connection, the
side-by-side view after an edit conflict, paging, and so on.

The Java tests (./mvnw test) cover the server rules. This drives a real Chrome against a
running app, with several people signed in at once.

    # 1. start the app on a FRESH database (the checks change data and expect the demo data)
    rm -rf data && ./mvnw spring-boot:run

    # 2. in another terminal
    python3 -m venv .venv && .venv/bin/pip install playwright
    .venv/bin/python e2e/ui_checks.py http://localhost:8080

    # optional: save screenshots, or also check paging against a big database
    .venv/bin/python e2e/ui_checks.py http://localhost:8080 --shots docs/screenshots
    rm -rf data && ./mvnw spring-boot:run -Dspring-boot.run.arguments=--newtonite.seed-bulk-items=20000
    .venv/bin/python e2e/ui_checks.py http://localhost:8080 --paging

It uses the Google Chrome you already have (no browser download). Exit code 0 = all passed.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument("base", help="where the app is running, e.g. http://localhost:8080")
parser.add_argument("--shots", help="folder to save screenshots in")
parser.add_argument("--paging", action="store_true", help="only run the paging checks (needs --newtonite.seed-bulk-items)")
args = parser.parse_args()
BASE = args.base.rstrip("/")
results = []


def check(name, condition, detail=""):
    results.append(bool(condition))
    print(("PASS " if condition else "FAIL ") + name + (f"   [{detail}]" if detail and not condition else ""))


def api(method, path, token=None, body=None):
    request = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request) as response:
            return json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}


def token_for(name):
    people = api("GET", "/api/users")
    return api("POST", "/api/login", body={"userId": next(p["id"] for p in people if p["name"].startswith(name))})["token"]


class Chrome:
    def __init__(self, playwright):
        self.browser = playwright.chromium.launch(channel="chrome", headless=True)
        self.page_errors = []

    def page(self, **options):
        context = self.browser.new_context(**{"viewport": {"width": 1440, "height": 900}, **options})
        page = context.new_page()
        page.on("pageerror", lambda e: self.page_errors.append(str(e)))  # uncaught JavaScript errors
        return page


def shot(page, name):
    if args.shots:
        page.screenshot(path=f"{args.shots}/{name}.png")


def sign_in(page, name):
    page.goto(BASE)
    page.wait_for_selector(".person")
    page.click(f".person:has-text('{name}')")
    page.wait_for_selector("#list .row, #list .empty-state")
    page.wait_for_function("document.querySelector('#overview .stat-number') && document.querySelector('#overview .stat-number').textContent !== '–'")


def search(page, text):
    page.fill("#search", text)
    page.wait_for_timeout(700)


def open_item(page, text):
    search(page, text)
    page.click(f"#list .row:has-text('{text}')")
    page.wait_for_selector("#f-title")
    page.wait_for_timeout(300)


def card(page, label):
    return int(page.locator(f"#overview .stat:has-text('{label}') .stat-number").inner_text().replace(",", ""))


def prop(page, label):
    return page.locator(f".d-props .prop:has-text('{label}') .prop-value").inner_text().strip()


def toasts(page):
    return " | ".join(page.locator("#toasts .toast").all_inner_texts())


def click_even_if_greyed_out(page, selector):
    # Actions you aren't allowed to take stay clickable on purpose (they explain why). Playwright
    # treats aria-disabled as disabled, so click through the DOM.
    page.evaluate(f"document.querySelector('{selector}').click()")


# ---------------------------------------------------------------------------------------------

def overview_and_filters(chrome):
    page = chrome.page()
    sign_in(page, "Asha")
    shot(page, "overview")
    active = card(page, "All active")
    check("overview cards show numbers", active > 0, str(active))

    for label in ["Needs an owner", "Urgent", "Waiting for approval", "Gone quiet", "Assigned to me"]:
        count = card(page, label)
        page.click(f"#overview .stat:has-text('{label}')")
        page.wait_for_timeout(600)
        shown = page.locator("#result-count").inner_text().replace(",", "")
        rows = page.locator("#list .row").count()
        check(f"card '{label}' ({count}) opens a list of the same size", rows == count and (count == 0 or str(count) in shown or shown.startswith(str(count))), f"rows={rows} shown='{shown}'")
        check(f"card '{label}' is highlighted", page.locator(f"#overview .stat:has-text('{label}')").get_attribute("aria-pressed") == "true")
    page.click("#overview .stat:has-text('Needs an owner')")
    page.wait_for_timeout(500)
    check("the filter drop-downs follow the card", page.input_value("#filter-assigned") == "unassigned")
    page.click("#overview .stat:has-text('Needs an owner')")
    page.wait_for_timeout(500)
    check("clicking the selected card again goes back to all active", page.input_value("#filter-assigned") == "" and card(page, "All active") == active)

    search(page, "settlement")
    check("search narrows the list", page.locator("#list .row").count() == 1)
    search(page, "qqqq-nothing-matches")
    check("a search with no matches shows an empty state", page.locator("#list .empty-state").count() == 1)
    page.click("#list .empty-state button")
    page.wait_for_timeout(500)
    check("'Clear filters' brings the list back", page.locator("#list .row").count() > 3 and page.input_value("#search") == "")
    return page


def two_people_claim_the_same_item(chrome, asha):
    open_item(asha, "Customer charged twice")
    check("a lead sees take ownership, approve and cancel", all(
        any(word in name for name in asha.locator(".d-actions .btn").all_inner_texts()) for word in ("Take ownership", "Approve", "Cancel")))
    check("the address bar holds the open item", "#/item/" in asha.url)
    item_id = int(asha.url.split("/")[-1])
    shot(asha, "detail")

    ben, chen = chrome.page(), chrome.page()
    sign_in(ben, "Ben")
    sign_in(chen, "Chen")
    open_item(ben, "Customer charged twice")
    open_item(chen, "Customer charged twice")
    ben.evaluate("document.querySelector('#act-claim').click()")   # both click in the same moment
    chen.evaluate("document.querySelector('#act-claim').click()")
    ben.wait_for_timeout(1500)
    chen.wait_for_timeout(1500)

    seen_by_ben, seen_by_chen = prop(ben, "Owner"), prop(chen, "Owner")
    check("both pages end up showing the same owner", seen_by_ben == seen_by_chen != "Nobody yet", f"{seen_by_ben} / {seen_by_chen}")
    winner = "Ben Okafor" if "Ben" in seen_by_ben else "Chen Wei"
    loser_page, winner_page = (chen, ben) if winner == "Ben Okafor" else (ben, chen)
    check("the person who lost was told who has it", "Already taken by" in toasts(loser_page), toasts(loser_page))
    detail = api("GET", f"/api/items/{item_id}", token_for("Asha"))
    check("the server agrees with both pages", detail["item"]["assignee"]["name"] == winner)
    check("the history has exactly one 'claimed'", [h["type"] for h in detail["history"]].count("claimed") == 1)
    shot(loser_page, "claim-lost")
    return item_id, winner_page, chen


def approval_flow(asha, winner_page, item_id):
    click_even_if_greyed_out(winner_page, "#act-resolve")
    winner_page.wait_for_timeout(300)
    check("the owner can't resolve before approval, and is told why", "approve" in toasts(winner_page).lower(), toasts(winner_page))
    asha.reload()
    asha.wait_for_selector("#f-title")
    asha.click("#act-approve")
    asha.wait_for_timeout(1300)
    check("a lead approves", "Approved" in prop(asha, "Approval"))
    winner_page.wait_for_timeout(5800)  # the owner's page refreshes by itself
    check("the owner's page picks up the approval without a reload", "Approved" in prop(winner_page, "Approval"))
    winner_page.click("#act-resolve")
    winner_page.wait_for_timeout(1300)
    check("the owner resolves it", "Resolved" in winner_page.locator(".d-top .pill").inner_text())
    asha.wait_for_timeout(5800)
    check("the lead is offered 'Close item'", asha.locator("#act-close").count() == 1)
    asha.click("#act-close")
    asha.wait_for_timeout(1200)
    check("the lead closes it", "Closed" in asha.locator(".d-top .pill").inner_text())


def edit_conflict(chrome, asha):
    farah = chrome.page()
    sign_in(farah, "Farah")
    open_item(asha, "Settlement total")
    open_item(farah, "Settlement total")

    asha.fill("#f-title", "Settlement total doesn't match the bank (checked)")  # typing, not saved yet
    farah.select_option("#f-priority", "low")
    farah.click("#save-button")  # Farah saves first
    farah.wait_for_timeout(500)

    # Asha's page notices on its next refresh, but must not disturb what she is typing.
    asha.wait_for_selector("#stale-note:not([hidden])", timeout=9000)
    check("someone else's save is flagged without wiping what I was typing", asha.input_value("#f-title").endswith("(checked)"))
    asha.click("#save-button")
    asha.wait_for_selector(".notice.conflict", timeout=5000)
    check("saving an out-of-date edit shows both versions", asha.locator(".notice.conflict").count() == 1)
    shot(asha, "conflict")
    asha.click("text=Apply my edits on top")
    asha.wait_for_timeout(1500)
    item = api("GET", "/api/items?q=Settlement", token_for("Asha"))["items"][0]
    check("applying on top keeps both people's changes", item["priority"] == "low" and item["title"].endswith("(checked)"), f"{item['priority']} / {item['title']}")
    history = api("GET", f"/api/items/{item['id']}", token_for("Asha"))["history"]
    check("the history shows both edits", [h["type"] for h in history].count("updated") == 2)


def comments_survive_a_dropped_connection(asha):
    open_item(asha, "help-center")
    asha.fill("#f-comment", "First comment")
    asha.click("#comment-button")
    asha.wait_for_timeout(1200)
    check("a comment appears once", asha.locator(".feed-comment:has-text('First comment')").count() == 1)

    asha.context.set_offline(True)
    asha.fill("#f-comment", "Sent while offline")
    asha.click("#comment-button")
    asha.wait_for_timeout(800)
    check("a comment that couldn't be sent says so and offers retry", asha.locator(".feed-comment.failed").count() == 1)
    shot(asha, "comment-not-sent")
    asha.context.set_offline(False)
    asha.click(".feed-comment.failed >> text=Retry")
    asha.wait_for_timeout(1500)
    check("retrying delivers it once", asha.locator(".feed-comment:has-text('Sent while offline')").count() == 1 and asha.locator(".feed-comment.failed").count() == 0)
    item_id = int(asha.url.split("/")[-1])
    stored = api("GET", f"/api/items/{item_id}", token_for("Asha"))["comments"]
    check("the server stored it exactly once", sum(1 for c in stored if c["body"] == "Sent while offline") == 1)


def team_access(chrome, hidden_item_id):
    erik = chrome.page()
    sign_in(erik, "Erik")
    shot(erik, "other-team")
    search(erik, "Settlement")
    check("someone in another team can't find the item by searching", erik.locator("#list .row").count() == 0)
    erik.goto(f"{BASE}#/item/{hidden_item_id}")
    erik.wait_for_timeout(1500)
    check("a link to an item you can't see says 'not found' and shows nothing", "not found" in toasts(erik).lower() and erik.locator("#f-title").count() == 0, toasts(erik))


def notifications_and_new_item(chrome, asha, chen):
    chen.reload()
    chen.wait_for_selector("#bell")
    chen.wait_for_timeout(1500)
    chen.click("#bell")
    chen.wait_for_timeout(300)
    check("the person who raised an item sees what happened to it", chen.locator("#inbox-list li").count() >= 2 and "Customer charged twice" in chen.locator("#inbox-list").inner_text())
    shot(chen, "notifications")

    asha.keyboard.press("Escape")
    asha.click("#new-item-button")
    asha.fill("#new-item-form input[name=title]", "Created by the browser check")
    asha.click("#new-item-form .seg label:has-text('Urgent')")
    shot(asha, "new-item")
    asha.dblclick("#new-item-submit")  # a double click must not create two items
    asha.wait_for_timeout(1500)
    found = api("GET", "/api/items?q=Created%20by%20the%20browser%20check", token_for("Asha"))["items"]
    check("double-clicking 'Create item' makes exactly one item", len(found) == 1, str(len(found)))
    check("the new item opens", asha.input_value("#f-title") == "Created by the browser check")

    asha.click("body", position={"x": 5, "y": 5})
    asha.keyboard.press("/")
    check("'/' focuses the search box", asha.evaluate("document.activeElement.id") == "search")
    asha.keyboard.press("Escape")
    asha.keyboard.press("n")
    check("'n' opens the new item dialog", asha.evaluate("document.querySelector('#new-item').open"))
    asha.keyboard.press("Escape")


def long_history(chrome):
    ben = token_for("Ben")
    item = api("POST", "/api/items", ben, {"teamId": 1, "title": "Long running incident", "priority": "high"})
    for i in range(1, 121):
        api("POST", f"/api/items/{item['id']}/comments", ben, {"body": f"status update {i}"})
    page = chrome.page()
    sign_in(page, "Asha")
    page.goto(f"{BASE}#/item/{item['id']}")
    page.wait_for_selector("#f-title")
    page.wait_for_timeout(800)
    check("an item with 120 comments opens with only the newest 50 entries", page.locator(".feed li").count() == 50)
    check("the heading still counts every comment", "120 comments" in page.locator(".activity h2").inner_text().lower())
    check("the newest comment comes first", "status update 120" in page.locator(".feed li").first.inner_text())
    shot(page, "long-history")
    page.click("#show-older")
    page.wait_for_timeout(800)
    page.click("#show-older")
    page.wait_for_timeout(800)
    check("'Show older activity' loads the rest, ending at the creation", page.locator(".feed li").count() == 121 and "raised this item" in page.locator(".feed li").last.inner_text())
    api("POST", f"/api/items/{item['id']}/comments", ben, {"body": "one more"})
    page.wait_for_timeout(5800)
    check("a refresh adds the new comment and keeps the older entries", page.locator(".feed li").count() == 122 and "one more" in page.locator(".feed li").first.inner_text())


def session_and_connection(chrome):
    victim = chrome.page()
    sign_in(victim, "Divya")
    api("POST", "/api/logout", victim.evaluate("sessionStorage.getItem('token')"))
    victim.wait_for_timeout(6000)
    check("an expired session returns to sign-in with an explanation", victim.locator("#signin").is_visible() and "session" in victim.locator("#signin-notice").inner_text().lower())

    page = chrome.page()
    sign_in(page, "Ben")
    page.context.set_offline(True)
    page.wait_for_timeout(5800)
    check("losing the connection shows a banner", page.locator("#offline").is_visible())
    shot(page, "offline")
    page.context.set_offline(False)
    page.wait_for_timeout(5800)
    check("the banner goes away when it's back", not page.locator("#offline").is_visible())


def looks(chrome):
    dark = chrome.page(color_scheme="dark")
    sign_in(dark, "Asha")
    open_item(dark, "Merchant payout")
    shot(dark, "dark")

    phone = chrome.page(viewport={"width": 390, "height": 844}, device_scale_factor=2, is_mobile=True, has_touch=True)
    sign_in(phone, "Asha")
    shot(phone, "mobile-list")
    phone.click("#filters-toggle")
    phone.wait_for_timeout(300)
    shot(phone, "mobile-filters")
    phone.click("#filters-toggle")
    phone.click("#list .row >> nth=0")
    phone.wait_for_selector("#f-title")
    phone.wait_for_timeout(400)
    check("on a phone the item replaces the list", phone.locator("#list-pane").is_hidden() and phone.locator("#f-title").is_visible())
    shot(phone, "mobile-detail")
    phone.go_back()
    phone.wait_for_timeout(500)
    check("the phone's back button returns to the list", phone.locator("#list-pane").is_visible() and phone.locator("#f-title").count() == 0)
    check("nothing scrolls sideways on a phone", not phone.evaluate("document.documentElement.scrollWidth > document.documentElement.clientWidth"))


def paging(chrome):
    page = chrome.page()
    sign_in(page, "Asha")
    page.wait_for_timeout(800)
    check("first page is 30 rows", page.locator("#list .row").count() == 30)
    check("the count says 'Showing 30 of ...'", page.locator("#result-count").inner_text().startswith("Showing 30 of"))
    page.evaluate("document.querySelector('#list-pane').scrollTop = document.querySelector('#list-pane').scrollHeight")
    page.wait_for_function("document.querySelectorAll('#list .row').length >= 60", timeout=5000)
    check("scrolling to the end loads the next page by itself", page.locator("#list .row").count() >= 60)
    for _ in range(3):
        loaded = page.locator("#list .row").count()
        page.evaluate("document.querySelector('#list-pane').scrollTop = document.querySelector('#list-pane').scrollHeight")
        page.wait_for_function(f"document.querySelectorAll('#list .row').length > {loaded}", timeout=5000)
    ids = page.eval_on_selector_all("#list li[data-id]", "els => els.map(e => e.dataset.id)")
    check("no row appears twice after several pages", len(ids) == len(set(ids)))

    api("POST", "/api/items", token_for("Chen"), {"teamId": 1, "title": "Brand new while you were scrolling", "priority": "urgent"})
    page.wait_for_selector(".updates", timeout=9000)
    check("a deep list gets an 'updates available' bar instead of jumping around", page.locator(".updates").count() == 1)
    page.click(".updates button")
    page.wait_for_timeout(800)
    check("refreshing goes back to the top with the new item first",
          page.locator("#list .row .row-title").first.inner_text() == "Brand new while you were scrolling" and page.locator("#list .row").count() == 30)

    page.fill("#search", "refund")
    page.wait_for_timeout(100)
    page.fill("#search", "chargeback")
    page.wait_for_timeout(1500)
    titles = page.locator("#list .row .row-title").all_inner_texts()
    check("fast typing: only the last search's results are shown", titles and all("chargeback" in t.lower() for t in titles))


with sync_playwright() as playwright:
    chrome = Chrome(playwright)
    if args.paging:
        paging(chrome)
    else:
        asha = overview_and_filters(chrome)
        refund_id, winner_page, chen = two_people_claim_the_same_item(chrome, asha)
        approval_flow(asha, winner_page, refund_id)
        edit_conflict(chrome, asha)
        comments_survive_a_dropped_connection(asha)
        team_access(chrome, refund_id)
        notifications_and_new_item(chrome, asha, chen)
        long_history(chrome)
        session_and_connection(chrome)
        looks(chrome)
    check("no uncaught JavaScript errors in any tab", not chrome.page_errors, str(chrome.page_errors[:3]))
    chrome.browser.close()

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
