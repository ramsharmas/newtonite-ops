// Newtonite Ops front end: one page, plain JavaScript, no build step.
// It only talks to the JSON API under /api, so the backend can change language
// without touching this file.

const POLL_MS = 5000;

const ACTION_LABELS = {
	claim: 'Take ownership',
	release: 'Release',
	approve: 'Approve',
	resolve: 'Resolve',
	close: 'Close',
	reopen: 'Reopen',
	cancel: 'Cancel item',
};

const HIDDEN_WHEN = ['invalid_transition', 'no_approval_needed', 'already_approved'];

const state = {
	// sessionStorage, not localStorage, so two tabs can be signed in as different people.
	token: sessionStorage.getItem('token'),
	me: null,
	items: [],
	selectedId: null,
	detail: null, // the item as it was when the page loaded it
	conflict: null, // { current, mine } after a refused save
	inbox: { unread: 0, notifications: [] },
	filters: { q: '', teamId: '', status: 'active', assigned: '', sort: 'updated' },
	newItemKey: null,
	commentKey: null,
	timer: null,
};

const $ = (selector) => document.querySelector(selector);

// Small DOM builder. Text always goes in as text nodes, so nothing from the API is
// ever parsed as HTML.
function el(tag, attrs = {}, ...children) {
	const node = document.createElement(tag);
	for (const [key, value] of Object.entries(attrs)) {
		if (value == null || value === false) continue;
		if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
		else if (key === 'class') node.className = value;
		else node.setAttribute(key, value === true ? '' : value);
	}
	for (const child of children.flat()) {
		if (child == null || child === false) continue;
		node.append(child instanceof Node ? child : document.createTextNode(String(child)));
	}
	return node;
}

// ---- API ------------------------------------------------------------------------------

class ApiError extends Error {
	constructor(status, body) {
		super(body.message || `Request failed (${status})`);
		this.status = status;
		this.body = body;
	}
}

async function api(method, path, body, headers = {}) {
	const response = await fetch(path, {
		method,
		headers: {
			'Content-Type': 'application/json',
			...(state.token ? { Authorization: `Bearer ${state.token}` } : {}),
			...headers,
		},
		body: body === undefined ? undefined : JSON.stringify(body),
	});
	const text = await response.text();
	const data = text ? JSON.parse(text) : {};
	if (response.status === 401 && state.token) {
		forgetSignIn();
		showSignIn();
	}
	if (!response.ok) throw new ApiError(response.status, data);
	return data;
}

// ---- Sign in --------------------------------------------------------------------------

async function showSignIn() {
	clearInterval(state.timer);
	$('#app').hidden = true;
	$('#signin').hidden = false;
	const people = await api('GET', '/api/users');
	$('#people').replaceChildren(...people.map((person) =>
		el('button', { class: 'person', type: 'button', onclick: () => signIn(person.id) },
			el('span', { class: 'avatar' }, initials(person.name)),
			el('span', {},
				el('strong', {}, person.name),
				el('small', {}, person.teams.map((t) => `${t.name} ${t.role}`).join(' · '))))));
}

async function signIn(userId) {
	const result = await api('POST', '/api/login', { userId });
	state.token = result.token;
	sessionStorage.setItem('token', result.token);
	await start();
}

async function signOut() {
	try {
		await api('POST', '/api/logout');
	}
	finally {
		forgetSignIn();
		showSignIn();
	}
}

function forgetSignIn() {
	sessionStorage.removeItem('token');
	Object.assign(state, { token: null, me: null, selectedId: null, detail: null, conflict: null });
}

async function start() {
	state.me = await api('GET', '/api/me');
	$('#signin').hidden = true;
	$('#app').hidden = false;
	$('#me-name').textContent = state.me.name;
	$('#filter-team').replaceChildren(
		el('option', { value: '' }, 'All my teams'),
		...state.me.teams.map((t) => el('option', { value: t.id }, `${t.name} (${t.role})`)));
	$('#detail').replaceChildren(el('p', { class: 'empty' }, 'Pick an item to see it here.'));
	await Promise.all([loadItems(), loadInbox()]);
	clearInterval(state.timer);
	state.timer = setInterval(poll, POLL_MS);
}

// ---- Item list ------------------------------------------------------------------------

async function loadItems() {
	const params = new URLSearchParams();
	for (const [key, value] of Object.entries(state.filters)) {
		if (value) params.set(key, value);
	}
	state.items = (await api('GET', `/api/items?${params}`)).items;
	renderList();
}

function renderList() {
	const list = $('#list');
	if (!state.items.length) {
		list.replaceChildren(el('li', { class: 'empty' }, 'Nothing here. Try another filter.'));
		return;
	}
	list.replaceChildren(...state.items.map((item) =>
		el('li', { class: item.id === state.selectedId ? 'selected' : null },
			el('button', { type: 'button', onclick: () => openItem(item.id) },
				el('div', { class: 'item-title' },
					el('span', { class: `dot ${item.priority}`, title: `${item.priority} priority` }),
					item.title),
				el('div', { class: 'item-meta' },
					`${item.team.name} · ${statusLabel(item.status)} · ${item.assignee ? item.assignee.name : 'Nobody yet'}`,
					needsApproval(item) ? ' · needs approval' : '',
					item.kind === 'incident' ? ' · incident' : '')))));
}

// ---- Item detail ----------------------------------------------------------------------

async function openItem(id) {
	try {
		const detail = await api('GET', `/api/items/${id}`);
		Object.assign(state, { selectedId: id, detail, conflict: null, commentKey: null });
		renderDetail();
		renderList();
	}
	catch (e) {
		toast(e.message, true);
	}
}

function renderDetail() {
	const { item, actions, comments, history, myRole } = state.detail;
	const finished = item.status === 'closed' || item.status === 'cancelled';

	// Hide actions that make no sense for this item right now; grey out the ones the
	// person isn't allowed to do, so they can click and see why.
	const visibleActions = actions.filter((a) => a.allowed || !HIDDEN_WHEN.includes(a.code));

	$('#detail').replaceChildren(
		el('div', { class: 'detail-head' },
			el('span', {}, `#${item.id} · ${item.team.name} · you are ${myRole === 'lead' ? 'a lead' : 'a member'} here`),
			el('span', { class: `pill status-${item.status}` }, statusLabel(item.status)),
			item.kind === 'incident' ? el('span', { class: 'pill warn' }, 'incident') : null,
			needsApproval(item) ? el('span', { class: 'pill warn' }, 'needs approval') : null),

		el('form', { id: 'edit-form', onsubmit: save, oninput: updateSaveButton },
			el('label', { class: 'field' }, el('span', {}, 'Title'),
				el('input', { id: 'f-title', class: 'title-input', value: item.title, maxlength: 200, required: true, disabled: finished })),
			el('label', { class: 'field' }, el('span', {}, 'Description'),
				textarea('f-description', item.description || '', 4, finished)),
			el('div', { class: 'row' },
				el('label', { class: 'field' }, el('span', {}, 'Priority'),
					select('f-priority', ['urgent', 'high', 'medium', 'low'], item.priority, finished)),
				el('div', { class: 'field' },
					el('button', { id: 'save-button', class: 'btn primary', type: 'submit', disabled: true }, 'Save changes')),
				el('span', { class: 'field muted', title: 'Goes up by one on every change' }, `version ${item.version}`)),
			el('p', { id: 'stale-note', class: 'notice', hidden: true },
				'Someone else just changed this item. If you save, you will see both versions and can choose.')),

		el('div', { id: 'conflict' }),

		el('div', { class: 'facts' },
			fact('Owner', item.assignee ? item.assignee.name : 'Nobody yet'),
			fact('Raised by', item.createdBy.name),
			fact('Approval', !item.requiresApproval ? 'Not needed'
				: item.approvedBy ? `Approved by ${item.approvedBy.name}` : 'Waiting for a lead'),
			fact('Last change', ago(item.updatedAt))),

		el('div', { class: 'actions' }, visibleActions.map((action) =>
			el('button', {
				type: 'button',
				class: `btn${action.allowed ? '' : ' unavailable'}`,
				title: action.reason,
				'aria-disabled': action.allowed ? null : 'true',
				onclick: () => action.allowed ? runAction(action.name) : toast(action.reason, true),
			}, ACTION_LABELS[action.name] || action.name))),

		el('h3', {}, `Comments (${comments.length})`),
		el('ul', { class: 'timeline' }, comments.map((c) =>
			el('li', {}, el('strong', {}, c.author.name), el('small', {}, ago(c.createdAt)),
				el('div', { class: 'comment-body' }, c.body)))),
		el('form', { class: 'comment-form', onsubmit: addComment },
			textarea('f-comment', '', 2, false, 'Write a comment'),
			el('button', { id: 'comment-button', class: 'btn', type: 'submit' }, 'Add comment')),

		el('h3', {}, 'History'),
		el('ul', { class: 'timeline' }, [...history].reverse().map((event) =>
			el('li', {}, describeEvent(event), el('small', {}, ago(event.createdAt))))));

	renderConflict();
}

function textarea(id, value, rows, disabled, placeholder) {
	const box = el('textarea', { id, rows, disabled, placeholder, maxlength: id === 'f-comment' ? 2000 : 4000 });
	box.value = value;
	return box;
}

function select(id, options, value, disabled) {
	const box = el('select', { id, disabled }, options.map((o) => el('option', { value: o }, capitalize(o))));
	box.value = value;
	return box;
}

function fact(label, value) {
	return el('div', {}, el('span', {}, label), value);
}

// ---- Editing and conflicts ------------------------------------------------------------

function formValues() {
	return {
		title: $('#f-title').value.trim(),
		description: $('#f-description').value.trim(),
		priority: $('#f-priority').value,
	};
}

// Only the fields this person actually changed. Sending just these is what lets us
// re-apply them on top of someone else's newer version without undoing their work.
function myChanges(item) {
	const form = formValues();
	const changes = {};
	if (form.title !== item.title) changes.title = form.title;
	if (form.description !== (item.description || '')) changes.description = form.description;
	if (form.priority !== item.priority) changes.priority = form.priority;
	return changes;
}

function isDirty() {
	return Boolean(state.detail && $('#f-title') && Object.keys(myChanges(state.detail.item)).length);
}

function updateSaveButton() {
	$('#save-button').disabled = !isDirty();
}

async function save(event) {
	event.preventDefault();
	const item = state.detail.item;
	const changes = myChanges(item);
	if (!Object.keys(changes).length) return;
	await submitChanges(item.id, item.version, changes);
}

async function submitChanges(id, version, changes) {
	try {
		await api('PATCH', `/api/items/${id}`, { version, ...changes });
		toast('Saved');
		await openItem(id);
		loadItems();
	}
	catch (e) {
		if (e.body.error === 'conflict') {
			state.conflict = { current: e.body.current, mine: changes };
			renderConflict();
		}
		else {
			toast(e.message, true);
		}
	}
}

function renderConflict() {
	const box = $('#conflict');
	if (!box) return;
	if (!state.conflict) {
		box.replaceChildren();
		return;
	}
	const { current, mine } = state.conflict;
	const fields = ['title', 'description', 'priority'];
	$('#stale-note').hidden = true;
	box.replaceChildren(el('div', { class: 'notice' },
		el('strong', {}, 'Someone else saved this item while you were editing.'),
		el('div', { class: 'muted' }, 'Nothing was overwritten. Compare the two versions:'),
		el('table', {},
			el('tr', {}, el('th', {}, 'Field'), el('th', {}, 'Saved version'), el('th', {}, 'Your edit')),
			fields.map((f) => el('tr', {},
				el('td', {}, capitalize(f)),
				el('td', {}, current[f] || '(empty)'),
				el('td', {}, f in mine ? (mine[f] || '(empty)') : el('span', { class: 'muted' }, 'not changed'))))),
		el('div', { class: 'row' },
			el('button', {
				class: 'btn primary', type: 'button',
				onclick: () => submitChanges(current.id, current.version, mine),
			}, 'Apply my edits on top'),
			el('button', {
				class: 'btn', type: 'button',
				onclick: () => openItem(current.id),
			}, 'Discard my edits'))));
}

// ---- Actions and comments -------------------------------------------------------------

async function runAction(name) {
	const id = state.detail.item.id;
	const path = name === 'claim' ? `/api/items/${id}/claim` : `/api/items/${id}/actions/${name}`;
	try {
		await api('POST', path);
		toast(`${ACTION_LABELS[name]}: done`);
	}
	catch (e) {
		toast(e.message, true);
	}
	await openItem(id);
	loadItems();
}

async function addComment(event) {
	event.preventDefault();
	const box = $('#f-comment');
	const body = box.value.trim();
	if (!body) return;
	const id = state.detail.item.id;
	// Same key for every retry of this comment, so a double click or a retry after a
	// network error can't post it twice.
	state.commentKey ||= crypto.randomUUID();
	$('#comment-button').disabled = true;
	try {
		await api('POST', `/api/items/${id}/comments`, { body }, { 'Idempotency-Key': state.commentKey });
		await openItem(id);
	}
	catch (e) {
		toast(e.message, true);
		$('#comment-button').disabled = false;
	}
}

// ---- New item -------------------------------------------------------------------------

function openNewItem() {
	const form = $('#new-item-form');
	const fields = form.elements;
	form.reset();
	fields.teamId.replaceChildren(...state.me.teams.map((t) => el('option', { value: t.id }, t.name)));
	if (state.filters.teamId) fields.teamId.value = state.filters.teamId;
	// One key per new item. If the request is retried or the button is clicked twice,
	// the server sees the same key and creates the item only once.
	state.newItemKey = crypto.randomUUID();
	$('#new-item').showModal();
	fields.title.focus();
}

async function createItem(event) {
	event.preventDefault();
	const form = event.target;
	const fields = form.elements;
	const submit = form.querySelector('[type=submit]');
	submit.disabled = true;
	try {
		const item = await api('POST', '/api/items', {
			teamId: Number(fields.teamId.value),
			title: fields.title.value,
			description: fields.description.value,
			priority: fields.priority.value,
			kind: fields.kind.value,
			requiresApproval: fields.requiresApproval.checked,
		}, { 'Idempotency-Key': state.newItemKey });
		$('#new-item').close();
		toast('Item created');
		await loadItems();
		openItem(item.id);
	}
	catch (e) {
		toast(e.message, true);
	}
	finally {
		submit.disabled = false;
	}
}

// ---- Notifications --------------------------------------------------------------------

async function loadInbox() {
	state.inbox = await api('GET', '/api/notifications');
	const badge = $('#unread');
	badge.hidden = state.inbox.unread === 0;
	badge.textContent = state.inbox.unread > 9 ? '9+' : state.inbox.unread;
	renderInbox();
}

function renderInbox() {
	const list = $('#inbox-list');
	if (!state.inbox.notifications.length) {
		list.replaceChildren(el('li', { class: 'empty' }, 'No notifications yet.'));
		return;
	}
	list.replaceChildren(...state.inbox.notifications.map((n) =>
		el('li', { class: n.read ? null : 'unread' },
			el('button', {
				type: 'button',
				onclick: () => {
					toggleInbox(false);
					openItem(n.itemId);
				},
			}, n.message, el('small', {}, ago(n.createdAt))))));
}

function toggleInbox(open = $('#inbox').hidden) {
	$('#inbox').hidden = !open;
	$('#bell').setAttribute('aria-expanded', String(open));
}

async function markAllRead() {
	state.inbox = await api('POST', '/api/notifications/read');
	loadInbox();
}

// ---- Keeping the page fresh -----------------------------------------------------------

// Every few seconds: refresh the list and inbox, and the open item unless the person is
// in the middle of typing something there.
async function poll() {
	if (document.hidden || !state.token) return;
	try {
		await Promise.all([loadItems(), loadInbox()]);
		if (!state.selectedId || state.conflict) return;
		const fresh = await api('GET', `/api/items/${state.selectedId}`);
		const changed = fresh.item.version !== state.detail.item.version
			|| fresh.history.length !== state.detail.history.length;
		if (!changed) return;
		const busy = isDirty() || $('#f-comment').value.trim() || $('#detail').contains(document.activeElement);
		if (busy) {
			$('#stale-note').hidden = false;
		}
		else {
			state.detail = fresh;
			renderDetail();
		}
	}
	catch {
		// Try again next time.
	}
}

// ---- Helpers --------------------------------------------------------------------------

function describeEvent(event) {
	const who = event.actor ? event.actor.name : 'Someone';
	switch (event.type) {
		case 'created': return `${who} created this item`;
		case 'claimed': return `${who} took ownership`;
		case 'released': return `${who} released it`;
		case 'commented': return `${who} commented`;
		case 'updated': {
			const changes = Object.entries((event.detail && event.detail.changes) || {}).map(([field, c]) =>
				field === 'description' ? 'the description' : `${field} from "${c.from}" to "${c.to}"`);
			return `${who} changed ${changes.join(', ')}`;
		}
		default: return `${who} ${event.type} it`;
	}
}

function needsApproval(item) {
	return item.requiresApproval && !item.approvedBy;
}

function statusLabel(status) {
	return capitalize(status.replace('_', ' '));
}

function capitalize(text) {
	return text.charAt(0).toUpperCase() + text.slice(1);
}

function initials(name) {
	return name.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase();
}

function ago(iso) {
	const seconds = (Date.now() - new Date(iso).getTime()) / 1000;
	if (seconds < 60) return 'just now';
	if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
	if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
	return `${Math.floor(seconds / 86400)} d ago`;
}

function toast(message, isError = false) {
	const note = el('div', { class: `toast${isError ? ' error' : ''}`, role: isError ? 'alert' : 'status' }, message);
	$('#toasts').append(note);
	setTimeout(() => note.remove(), isError ? 5000 : 2500);
}

function debounce(fn, ms) {
	let timer;
	return (...args) => {
		clearTimeout(timer);
		timer = setTimeout(() => fn(...args), ms);
	};
}

// ---- Wiring ---------------------------------------------------------------------------

function wire() {
	$('#search').addEventListener('input', debounce((e) => {
		state.filters.q = e.target.value.trim();
		loadItems();
	}, 250));
	for (const [id, key] of [['filter-team', 'teamId'], ['filter-status', 'status'], ['filter-assigned', 'assigned'], ['filter-sort', 'sort']]) {
		$(`#${id}`).addEventListener('change', (e) => {
			state.filters[key] = e.target.value;
			loadItems();
		});
	}
	$('#new-item-button').addEventListener('click', openNewItem);
	$('#new-item-form').addEventListener('submit', createItem);
	$('#new-item-cancel').addEventListener('click', () => $('#new-item').close());
	$('#bell').addEventListener('click', () => toggleInbox());
	$('#mark-read').addEventListener('click', markAllRead);
	$('#sign-out').addEventListener('click', signOut);
	document.addEventListener('click', (e) => {
		if (!e.target.closest('.inbox-wrap')) toggleInbox(false);
	});
}

wire();
if (state.token) {
	start().catch(() => {
		forgetSignIn();
		showSignIn();
	});
}
else {
	showSignIn();
}
