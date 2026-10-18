// The open work item: what it is, who owns it, what to do next, what happened so far.
//
// Three things here exist because several people use the same item at once:
//  - Edits carry the item's version. If someone saved first, the server refuses (409) and
//    we show both versions instead of silently overwriting (see renderConflict).
//  - "Take ownership" and "comment" are optimistic: the page shows the result immediately
//    and then reconciles with what the server decided (see claim and sendComment).
//  - Unsaved typing is kept across refreshes, so a background update never eats someone's work.

import { api } from './api.js';
import { emit, state } from './state.js';
import { $, ago, avatar, capitalize, confirmDialog, el, fullTime, icon, statusLabel, toast, uuid, when } from './dom.js';
import { patchListItem, syncItem } from './list.js';

const ACTION_LABELS = {
	claim: 'Take ownership', release: 'Release', approve: 'Approve', resolve: 'Mark resolved',
	close: 'Close item', reopen: 'Reopen', cancel: 'Cancel item',
};
const ACTION_ICONS = {
	claim: 'userPlus', release: 'userMinus', approve: 'shield', resolve: 'check', close: 'check', reopen: 'refresh', cancel: 'x',
};
const ACTION_DONE = {
	release: 'Released', approve: 'Approved', resolve: 'Marked resolved', close: 'Closed', reopen: 'Reopened', cancel: 'Cancelled',
};
// Actions that can't apply to the item right now are hidden. Ones that apply but aren't
// allowed for this person stay visible and greyed out, with the reason as a tooltip.
// ("already_claimed" is hidden too: if someone else's claim wins a race, the server still
// says so, but a button for something already done would only confuse.)
const HIDDEN_WHEN = ['invalid_transition', 'no_approval_needed', 'already_approved', 'already_claimed'];
const PRIORITIES = ['urgent', 'high', 'medium', 'low'];
const STEPS = ['open', 'in_progress', 'resolved', 'closed'];
const FIELDS = ['title', 'description', 'priority'];

let busy = null; // the action being sent right now, if any
let loadNumber = 0;

// ---- Opening and refreshing ----------------------------------------------------------

export async function openItem(id) {
	if (state.selectedId !== id) {
		Object.assign(state, { draft: null, conflict: null, staleNote: false, detail: null });
		busy = null;
	}
	state.selectedId = id;
	renderDetail();
	await loadDetail(id);
}

export async function loadDetail(id = state.selectedId) {
	if (id == null) return;
	const mine = ++loadNumber;
	try {
		const detail = keepOlder(state.detail, await api('GET', `/api/items/${id}`));
		if (mine !== loadNumber || id !== state.selectedId) return;
		state.detail = detail;
		state.staleNote = false;
		renderDetail();
		syncItem(detail.item);
	}
	catch (e) {
		if (mine !== loadNumber || id !== state.selectedId) return;
		toast(e.message, true);
		// An item that isn't there (or isn't ours to see) has nothing to show.
		if (e.status === 404 || !state.detail) closeItem();
	}
}

export function closeItem() {
	Object.assign(state, { selectedId: null, detail: null, draft: null, conflict: null, staleNote: false });
	busy = null;
	renderDetail();
	emit('item-closed');
}

/** Called every few seconds. Picks up other people's changes to the open item. */
export async function pollDetail() {
	const id = state.selectedId;
	if (id == null || !state.detail || state.conflict || busy) return;
	if ((state.pendingComments.get(id) ?? []).some((entry) => entry.status === 'sending')) return;
	const fresh = await api('GET', `/api/items/${id}`);
	if (id !== state.selectedId || busy) return;
	const old = state.detail;
	const changed = fresh.item.version !== old.item.version
		|| fresh.historyTotal !== old.historyTotal
		|| fresh.commentsTotal !== old.commentsTotal;
	if (!changed) return;
	if (isDirty()) {
		// Don't pull the page out from under someone who is typing. They'll get the
		// side-by-side comparison if they save (see renderConflict).
		state.staleNote = true;
		$('#stale-note')?.removeAttribute('hidden');
		return;
	}
	state.detail = keepOlder(old, fresh);
	renderDetail();
}

/**
 * The server sends only the newest activity. If the person already clicked "show older",
 * a refresh must not throw that away: keep what they loaded that is older than the new window.
 */
function keepOlder(old, fresh) {
	if (!old || old.item.id !== fresh.item.id || !fresh.history.length) return fresh;
	const windowStart = fresh.history[0].id;
	const older = old.history.filter((event) => event.id < windowStart);
	if (!older.length) return fresh;
	const known = new Set(fresh.comments.map((comment) => comment.id));
	return {
		...fresh,
		history: [...older, ...fresh.history],
		comments: [...old.comments.filter((comment) => !known.has(comment.id)), ...fresh.comments],
		hasOlderHistory: old.hasOlderHistory,
	};
}

async function showOlder() {
	const detail = state.detail;
	const id = detail.item.id;
	const button = $('#show-older');
	if (button) {
		button.disabled = true;
		button.textContent = 'Loading…';
	}
	try {
		const page = await api('GET', `/api/items/${id}/history?before=${detail.history[0].id}`);
		if (state.selectedId !== id || !state.detail) return;
		state.detail = {
			...state.detail,
			history: [...page.history, ...state.detail.history],
			comments: [...page.comments, ...state.detail.comments],
			hasOlderHistory: page.hasOlderHistory,
		};
	}
	catch (e) {
		toast(e.message, true);
	}
	renderDetail();
}

// ---- The person's unsaved edits ------------------------------------------------------

function draftOf(item) {
	return state.draft && state.draft.itemId === item.id
		? state.draft
		: { itemId: item.id, title: item.title, description: item.description || '', priority: item.priority };
}

/** Only the fields this person actually changed. Sending just these is what lets us re-apply
 *  them on top of someone else's newer version without undoing their work. */
function myChanges(item) {
	if (!state.draft || state.draft.itemId !== item.id) return {};
	const draft = state.draft;
	const changes = {};
	if (draft.title.trim() !== item.title) changes.title = draft.title.trim();
	if (draft.description.trim() !== (item.description || '')) changes.description = draft.description.trim();
	if (draft.priority !== item.priority) changes.priority = draft.priority;
	return changes;
}

export const isDirty = () => Boolean(state.detail && Object.keys(myChanges(state.detail.item)).length);

function onEdit() {
	const item = state.detail.item;
	state.draft = {
		itemId: item.id,
		title: $('#f-title').value,
		description: $('#f-description').value,
		priority: $('#f-priority').value,
	};
	updateSaveBar();
}

function updateSaveBar() {
	const bar = $('#savebar');
	if (!bar) return;
	const dirty = isDirty();
	bar.hidden = !dirty;
	const titleEmpty = state.draft && state.draft.title.trim() === '';
	$('#save-button').disabled = !dirty || titleEmpty || busy === 'save';
	$('#savebar-note').textContent = titleEmpty ? 'The title can\'t be empty.' : 'You have unsaved changes.';
}

async function save() {
	const item = state.detail.item;
	const changes = myChanges(item);
	if (!Object.keys(changes).length) return;
	await submitChanges(item.id, item.version, changes);
}

async function submitChanges(id, version, changes) {
	busy = 'save';
	updateSaveBar();
	try {
		await api('PATCH', `/api/items/${id}`, { body: { version, ...changes } });
		Object.assign(state, { draft: null, conflict: null });
		toast('Saved');
		busy = null;
		await loadDetail(id);
		emit('items-changed');
	}
	catch (e) {
		busy = null;
		if (e.code === 'conflict' && e.body.current) {
			state.conflict = { current: e.body.current, mine: changes };
			renderDetail();
		}
		else {
			toast(e.message, true);
			if (e.status === 409) await loadDetail(id);
			updateSaveBar();
		}
	}
}

// ---- Actions -------------------------------------------------------------------------

async function runAction(name) {
	if (name === 'claim') return claim();
	if (name === 'cancel') {
		const confirmed = await confirmDialog({
			title: 'Cancel this item?',
			message: 'Nobody will be able to edit it afterwards. It stays in the history.',
			confirmLabel: 'Cancel item',
			danger: true,
		});
		if (!confirmed) return;
	}
	const id = state.detail.item.id;
	busy = name;
	renderDetail();
	try {
		await api('POST', `/api/items/${id}/actions/${name}`);
		toast(ACTION_DONE[name] || 'Done');
	}
	catch (e) {
		toast(e.message, true);
	}
	busy = null;
	await loadDetail(id);
	emit('items-changed');
}

/**
 * Optimistic: show the person as the owner straight away, then ask the server. If somebody
 * else got there first the server says no (409) and we put things back the way they were.
 */
async function claim() {
	const before = state.detail;
	const id = before.item.id;
	const me = { id: state.me.id, name: state.me.name };
	busy = 'claim';
	state.detail = { ...before, item: { ...before.item, assignee: me, status: 'in_progress' } };
	renderDetail();
	patchListItem(id, { assignee: me, status: 'in_progress' });
	try {
		await api('POST', `/api/items/${id}/claim`);
		toast('You now own this item');
	}
	catch (e) {
		toast(e.message, true); // for example "Already taken by Ben Okafor"
		state.detail = before; // undo what we showed...
		renderDetail();
	}
	busy = null;
	await loadDetail(id); // ...and show what is really true
	emit('items-changed');
}

// ---- Comments ------------------------------------------------------------------------

function submitComment() {
	const box = $('#f-comment');
	const body = box.value.trim();
	if (!body) return;
	const id = state.selectedId;
	box.value = '';
	state.commentDrafts.delete(id);
	// The key stays the same for every retry of this comment, so a double click, or a retry
	// after a dropped connection, can't post it twice.
	const entry = { key: uuid(), body, status: 'sending', error: null };
	state.pendingComments.set(id, [...(state.pendingComments.get(id) ?? []), entry]);
	sendComment(id, entry);
}

async function sendComment(id, entry) {
	Object.assign(entry, { status: 'sending', error: null });
	if (id === state.selectedId) renderDetail();
	try {
		await api('POST', `/api/items/${id}/comments`, { body: { body: entry.body }, headers: { 'Idempotency-Key': entry.key } });
		entry.status = 'sent'; // hidden until the real comment arrives, so it never shows twice
		if (id === state.selectedId) await loadDetail(id);
		state.pendingComments.set(id, (state.pendingComments.get(id) ?? []).filter((other) => other !== entry));
		if (id === state.selectedId) renderDetail();
		emit('items-changed');
	}
	catch (e) {
		Object.assign(entry, { status: 'failed', error: e.message });
		if (id === state.selectedId) renderDetail();
	}
}

function discardComment(id, entry) {
	state.pendingComments.set(id, (state.pendingComments.get(id) ?? []).filter((other) => other !== entry));
	renderDetail();
}

// ---- Rendering -----------------------------------------------------------------------

export function renderDetail() {
	const pane = $('#detail');
	document.body.dataset.view = state.selectedId == null ? 'list' : 'detail';
	if (state.selectedId == null) {
		pane.replaceChildren(el('div', { class: 'empty-state big' },
			icon('inbox', 36), el('strong', {}, 'Pick an item'),
			el('p', {}, 'Choose something from the list to see its details, history and what to do next.'),
			el('p', { class: 'muted small' }, 'Tip: press j and k to move through the list, / to search, n for a new item.')));
		return;
	}
	if (!state.detail) {
		pane.replaceChildren(skeleton());
		return;
	}

	const ui = captureUi(pane);
	const { item, actions, comments, history, myRole } = state.detail;
	const finished = item.status === 'closed' || item.status === 'cancelled';
	const draft = draftOf(item);
	const can = Object.fromEntries(actions.map((action) => [action.name, action.allowed]));
	const step = nextStep(item, can);

	pane.replaceChildren(
		el('div', { class: 'detail-grid' },
			el('div', { class: 'd-head' },
				el('div', { class: 'd-top' },
					el('button', { class: 'btn ghost back', type: 'button', 'aria-label': 'Back to the list', onclick: () => emit('close-item') }, icon('back', 18), 'List'),
					el('span', { class: 'crumb' }, `#${item.id} · ${item.team.name}`),
					el('span', { class: `pill status-${item.status}` }, statusLabel(item.status)),
					item.kind === 'incident' ? el('span', { class: 'tag danger-soft' }, 'Incident') : null,
					busy ? el('span', { class: 'saving' }, el('span', { class: 'spinner' }), 'Saving…') : null),
				// A textarea that grows with the text, so a long title wraps instead of being cut off.
				el('textarea', {
					id: 'f-title', class: 'title-input', rows: 1, value: draft.title, maxlength: 200, disabled: finished,
					'aria-label': 'Title',
					oninput: (event) => { grow(event.target); onEdit(); },
					onkeydown: (event) => { if (event.key === 'Enter') { event.preventDefault(); save(); } },
				}),
				finished ? null : stepper(item),
				el('div', { class: `callout ${step.tone}` }, icon(step.tone === 'attention' ? 'alert' : 'clock', 18), el('div', {}, step.lines.map((line) => el('p', {}, line)))),
				el('p', { id: 'stale-note', class: 'notice', hidden: !state.staleNote || Boolean(state.conflict) },
					icon('refresh', 16), 'Someone else changed this item while you were editing. You can keep typing; when you save, you will see both versions and choose.'),
				state.conflict ? conflictPanel(item) : null),

			el('div', { class: 'd-actions' }, actions
				.filter((action) => action.allowed || !HIDDEN_WHEN.includes(action.code))
				.map((action) => actionButton(action, item))),

			el('section', { class: 'd-props', 'aria-label': 'Item details' }, properties(item, myRole, draft, finished)),

			el('div', { class: 'd-body' },
				el('label', { class: 'section-label', for: 'f-description' }, 'Description'),
				el('textarea', {
					id: 'f-description', class: 'description', rows: 5, maxlength: 4000, disabled: finished,
					placeholder: finished ? 'No description.' : 'Add the details people need to act on this.', oninput: onEdit,
				}),
				activity(state.detail, item))),

		el('div', { id: 'savebar', class: 'savebar', hidden: true },
			el('span', { id: 'savebar-note' }),
			el('button', { class: 'btn ghost', type: 'button', onclick: discardEdits }, 'Discard'),
			el('button', { id: 'save-button', class: 'btn primary', type: 'button', onclick: save }, 'Save changes')));

	$('#f-description').value = draft.description;
	grow($('#f-title'));
	updateSaveBar();
	restoreUi(pane, ui);
}

/** Makes a textarea exactly as tall as its text. (CSS field-sizing does this in newer browsers only.) */
function grow(box) {
	box.style.height = 'auto';
	box.style.height = `${box.scrollHeight}px`;
}

function discardEdits() {
	Object.assign(state, { draft: null, conflict: null, staleNote: false });
	renderDetail();
}

function captureUi(pane) {
	const active = document.activeElement;
	const inside = active && pane.contains(active) && active.id;
	return { top: pane.scrollTop, id: inside ? active.id : null, start: active?.selectionStart, end: active?.selectionEnd };
}

function restoreUi(pane, ui) {
	pane.scrollTop = ui.top;
	const node = ui.id && document.getElementById(ui.id);
	if (!node || node.disabled) return;
	node.focus({ preventScroll: true });
	if (ui.start != null && typeof node.setSelectionRange === 'function') {
		try {
			node.setSelectionRange(ui.start, ui.end);
		}
		catch { /* some inputs have no caret */ }
	}
}

function skeleton() {
	return el('div', { class: 'detail-grid', 'aria-busy': 'true' },
		el('div', { class: 'd-head' },
			el('span', { class: 'skeleton line' }), el('span', { class: 'skeleton title' }), el('span', { class: 'skeleton block' })),
		el('div', { class: 'd-body' }, el('span', { class: 'skeleton block tall' })));
}

// ---- Pieces --------------------------------------------------------------------------

function stepper(item) {
	const at = STEPS.indexOf(item.status);
	return el('ol', { class: 'stepper', 'aria-label': 'Progress' }, STEPS.map((step, index) =>
		el('li', { class: index < at ? 'done' : index === at ? 'current' : '', 'aria-current': index === at ? 'step' : null },
			el('span', { class: 'step-dot' }, index < at ? icon('check', 12) : null),
			el('span', {}, statusLabel(step)))));
}

/** What should happen next, in plain words, for the person looking at the item. */
function nextStep(item, can) {
	const mine = item.assignee && item.assignee.id === state.me.id;
	const waiting = item.requiresApproval && !item.approvedBy;
	const lines = [];
	let tone = 'info';
	switch (item.status) {
		case 'open':
			lines.push('Nobody owns this yet. Take ownership to start work.');
			tone = 'attention';
			break;
		case 'in_progress':
			lines.push(mine ? 'You own this. Mark it resolved when the work is done.' : `${item.assignee.name} is working on this.`);
			break;
		case 'resolved':
			lines.push(can.close ? 'Resolved. Close it to finish, or reopen it if the problem is still there.' : 'Resolved. Waiting for a team lead to close it.');
			if (can.close) tone = 'attention';
			break;
		case 'closed':
			lines.push('Closed. Reopen it if the problem comes back.');
			tone = 'neutral';
			break;
		default:
			lines.push('Cancelled. Nothing more to do.');
			tone = 'neutral';
	}
	if (waiting && (item.status === 'open' || item.status === 'in_progress')) {
		lines.push(can.approve
			? 'It needs a lead\'s approval before it can be resolved, and you can give it.'
			: 'It needs a team lead\'s approval before it can be resolved.');
		if (can.approve) tone = 'attention';
	}
	return { tone, lines };
}

function actionButton(action, item) {
	const primary = action.allowed && isPrimary(action.name, item);
	return el('button', {
		id: `act-${action.name}`,
		type: 'button',
		class: `btn${primary ? ' primary' : ''}${action.name === 'cancel' ? ' danger-ghost' : ''}${action.allowed ? '' : ' unavailable'}`,
		title: action.reason,
		'aria-disabled': action.allowed ? null : 'true',
		disabled: Boolean(busy),
		onclick: () => (action.allowed ? runAction(action.name) : toast(action.reason, true)),
	}, icon(ACTION_ICONS[action.name] || 'check', 15), ACTION_LABELS[action.name] || capitalize(action.name));
}

/** The one button that moves the item forward for this person gets the strong colour. */
function isPrimary(name, item) {
	const mine = item.assignee && item.assignee.id === state.me.id;
	return name === 'claim'
		|| name === 'approve'
		|| (name === 'resolve' && mine)
		|| name === 'close';
}

function properties(item, role, draft, finished) {
	const approval = !item.requiresApproval ? el('span', { class: 'muted' }, 'Not needed')
		: item.approvedBy ? el('span', { class: 'ok-text' }, icon('check', 14), `Approved by ${item.approvedBy.name}`)
			: el('span', { class: 'warn-text' }, 'Waiting for a lead');
	const priority = el('select', { id: 'f-priority', disabled: finished, 'aria-label': 'Priority', onchange: onEdit },
		PRIORITIES.map((value) => el('option', { value }, capitalize(value))));
	priority.value = draft.priority;
	return [
		el('h2', {}, 'Details'),
		prop('Owner', item.assignee
			? el('span', { class: 'person-inline' }, avatar(item.assignee, 'sm'), item.assignee.name, busy === 'claim' ? el('span', { class: 'spinner' }) : null)
			: el('span', { class: 'muted' }, 'Nobody yet')),
		prop('Priority', priority),
		prop('Approval', approval),
		prop('Team', `${item.team.name} (you are ${role === 'lead' ? 'a lead' : 'a member'})`),
		prop('Raised by', el('span', { class: 'person-inline' }, avatar(item.createdBy, 'sm'), item.createdBy.name)),
		prop('Created', when(item.createdAt)),
		prop('Last activity', when(item.updatedAt)),
		prop('Revision', el('span', { class: 'muted', title: 'Goes up by one every time the item changes. It is how the server notices two people editing at once.' }, `v${item.version}`)),
	];
}

const prop = (label, value) => el('div', { class: 'prop' }, el('span', { class: 'prop-label' }, label), el('div', { class: 'prop-value' }, value));

// ---- Conflicts -----------------------------------------------------------------------

/**
 * Shown when a save is refused because someone else saved first. Nothing has been lost on
 * either side: we show both and let the person decide.
 */
function conflictPanel(item) {
	const { current, mine } = state.conflict;
	const original = state.detail.item;
	const value = (source, field) => (source[field] == null || source[field] === '' ? '(empty)' : capitalizeIfPriority(field, source[field]));
	const theirs = FIELDS.filter((field) => (current[field] ?? '') !== (original[field] ?? ''));
	const both = FIELDS.filter((field) => field in mine && theirs.includes(field));
	return el('div', { class: 'notice conflict', role: 'alert' },
		el('strong', {}, 'Someone else saved this item while you were editing.'),
		el('p', { class: 'muted' }, 'Nothing was overwritten. Here is what is saved now next to what you changed:'),
		el('table', {},
			el('thead', {}, el('tr', {}, el('th', {}, 'Field'), el('th', {}, 'Saved now'), el('th', {}, 'Your edit'))),
			el('tbody', {}, FIELDS.filter((field) => field in mine || theirs.includes(field)).map((field) =>
				el('tr', { class: both.includes(field) ? 'clash' : '' },
					el('td', {}, capitalize(field)),
					el('td', {}, value(current, field), theirs.includes(field) ? el('small', {}, ' changed by someone else') : null),
					el('td', {}, field in mine ? value(mine, field) : el('span', { class: 'muted' }, 'not changed')))))),
		both.length ? el('p', { class: 'muted small' }, `You both changed the ${both.join(' and ')}. If you apply your edits, yours replace theirs for ${both.length > 1 ? 'those fields' : 'that field'}.`) : null,
		el('div', { class: 'row-buttons' },
			el('button', { class: 'btn primary', type: 'button', onclick: () => submitChanges(current.id, current.version, mine) }, 'Apply my edits on top'),
			el('button', { class: 'btn', type: 'button', onclick: discardEdits }, 'Discard my edits')));
}

const capitalizeIfPriority = (field, value) => (field === 'priority' ? capitalize(value) : value);

// ---- Activity ------------------------------------------------------------------------

const EVENT_ICONS = {
	created: 'flag', claimed: 'userPlus', released: 'back', approved: 'shield', resolved: 'check',
	closed: 'check', reopened: 'refresh', cancelled: 'x', updated: 'edit',
};

function activity(detail, item) {
	const pending = (state.pendingComments.get(item.id) ?? []).filter((entry) => entry.status !== 'sent');
	// The history is the timeline. A "commented" entry points at its comment, which we show as a bubble.
	const comments = new Map(detail.comments.map((comment) => [comment.id, comment]));
	const entries = detail.history
		.map((event) => {
			if (event.type !== 'commented') return { kind: 'event', event, order: event.id };
			const comment = comments.get(Number(event.detail && event.detail.commentId));
			return comment ? { kind: 'comment', comment, order: event.id } : null;
		})
		.filter(Boolean)
		.sort((a, b) => b.order - a.order); // newest first

	const draft = state.commentDrafts.get(item.id) ?? '';
	const box = el('textarea', {
		id: 'f-comment', rows: 2, maxlength: 2000, placeholder: 'Write a comment. Ctrl or ⌘ + Enter to send.',
		oninput: (event) => {
			state.commentDrafts.set(item.id, event.target.value);
			$('#comment-button').disabled = !event.target.value.trim();
		},
		onkeydown: (event) => {
			if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
				event.preventDefault();
				submitComment();
			}
		},
	});
	box.value = draft;

	return el('section', { class: 'activity' },
		el('h2', {}, `Activity (${detail.commentsTotal} ${detail.commentsTotal === 1 ? 'comment' : 'comments'})`),
		el('div', { class: 'composer' }, box,
			el('button', { id: 'comment-button', class: 'btn primary', type: 'button', disabled: !draft.trim(), onclick: submitComment }, 'Comment')),
		el('ul', { class: 'feed' },
			pending.slice().reverse().map((entry) => pendingComment(item.id, entry)),
			entries.map((entry) => entry.kind === 'comment' ? commentRow(entry.comment) : eventRow(entry.event))),
		detail.hasOlderHistory
			? el('button', { id: 'show-older', class: 'btn show-older', type: 'button', onclick: showOlder },
				`Show older activity (${Math.max(0, detail.historyTotal - detail.history.length).toLocaleString()} more)`)
			: null);
}

function commentRow(comment) {
	return el('li', { class: 'feed-comment' },
		avatar(comment.author, 'md'),
		el('div', { class: 'bubble' },
			el('div', { class: 'bubble-head' }, el('strong', {}, comment.author.name), when(comment.createdAt)),
			el('div', { class: 'comment-body' }, comment.body)));
}

function pendingComment(id, entry) {
	const failed = entry.status === 'failed';
	return el('li', { class: `feed-comment pending${failed ? ' failed' : ''}` },
		avatar({ id: state.me.id, name: state.me.name }, 'md'),
		el('div', { class: 'bubble' },
			el('div', { class: 'bubble-head' },
				el('strong', {}, state.me.name),
				failed ? el('span', { class: 'warn-text' }, 'Not sent') : el('span', { class: 'saving' }, el('span', { class: 'spinner' }), 'Sending…')),
			el('div', { class: 'comment-body' }, entry.body),
			failed ? el('div', { class: 'row-buttons' },
				el('span', { class: 'muted small' }, entry.error),
				el('button', { class: 'btn small', type: 'button', onclick: () => sendComment(id, entry) }, 'Retry'),
				el('button', { class: 'btn small ghost', type: 'button', onclick: () => discardComment(id, entry) }, 'Discard')) : null));
}

function eventRow(event) {
	const [who, what] = describeEvent(event);
	return el('li', { class: 'feed-event' },
		el('span', { class: `event-icon ${event.type}` }, icon(EVENT_ICONS[event.type] || 'clock', 12)),
		el('span', { class: 'event-text' }, el('strong', {}, who), ` ${what}`),
		el('time', { datetime: event.createdAt, title: fullTime(event.createdAt) }, ago(event.createdAt)));
}

function describeEvent(event) {
	const who = event.actor ? event.actor.name : 'Someone';
	switch (event.type) {
		case 'created': return [who, 'raised this item'];
		case 'claimed': return [who, 'took ownership'];
		case 'released': return [who, 'released it back to the queue'];
		case 'approved': return [who, 'approved it'];
		case 'resolved': return [who, 'marked it resolved'];
		case 'closed': return [who, 'closed it'];
		case 'reopened': return [who, 'reopened it'];
		case 'cancelled': return [who, 'cancelled it'];
		case 'updated': {
			const parts = Object.entries((event.detail && event.detail.changes) || {}).map(([field, change]) => {
				if (field === 'description') return 'edited the description';
				if (field === 'priority') return `changed the priority from ${capitalize(String(change.from))} to ${capitalize(String(change.to))}`;
				return `renamed it from "${change.from}" to "${change.to}"`;
			});
			return [who, parts.join(', ') || 'edited it'];
		}
		default: return [who, `${event.type} it`];
	}
}
