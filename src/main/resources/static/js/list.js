// The filter bar and the list of work items, with paging.
//
// The list never holds more than the pages the person has scrolled through. The server
// returns a page plus a cursor; "load more" sends the cursor back.

import { api } from './api.js';
import { DEFAULT_FILTERS, PAGE_SIZE, emit, state } from './state.js';
import { $, $$, avatar, debounce, el, icon, plural, statusLabel, toast, when, capitalize } from './dom.js';

let requestNumber = 0; // lets us ignore answers to requests that were overtaken by a newer one
let controller = null;
let observer = null;

// ---- Filters -------------------------------------------------------------------------

const SELECTS = [
	['filter-team', 'teamId'], ['filter-status', 'status'], ['filter-assigned', 'assigned'],
	['filter-priority', 'priority'], ['filter-attention', 'attention'], ['filter-sort', 'sort'],
];

/** Wires the filter controls. Call once, when the page loads. */
export function initToolbar() {
	for (const [id, key] of SELECTS) {
		$(`#${id}`).addEventListener('change', (event) => setFilters({ [key]: event.target.value }));
	}
	$('#search').addEventListener('input', debounce((event) => setFilters({ q: event.target.value.trim() }), 250));
	$('#clear-filters').addEventListener('click', () => {
		$('#search').value = '';
		setFilters({ ...DEFAULT_FILTERS });
	});
	$('#filters-toggle').addEventListener('click', () => {
		const open = $('#toolbar').classList.toggle('open');
		$('#filters-toggle').setAttribute('aria-expanded', String(open));
	});
}

/** Fills in the team choices for whoever just signed in. */
export function resetToolbar() {
	$('#filter-team').replaceChildren(
		el('option', { value: '' }, 'All my teams'),
		...state.me.teams.map((team) => el('option', { value: team.id }, team.name)));
	syncFilterControls();
}

export function setFilters(partial) {
	Object.assign(state.filters, partial);
	syncFilterControls();
	emit('filters-changed');
	return reloadList();
}

function syncFilterControls() {
	for (const [id, key] of SELECTS) $(`#${id}`).value = state.filters[key];
	$('#search').value = state.filters.q;
	const changed = Object.keys(DEFAULT_FILTERS).some((key) => state.filters[key] !== DEFAULT_FILTERS[key]);
	$('#clear-filters').hidden = !changed;
	const active = Object.keys(DEFAULT_FILTERS).filter((key) => key !== 'sort' && state.filters[key] !== DEFAULT_FILTERS[key]).length;
	const badge = $('#filters-count');
	badge.hidden = active === 0;
	badge.textContent = active;
}

function queryString(extra = {}) {
	const params = new URLSearchParams();
	for (const [key, value] of Object.entries(state.filters)) {
		if (value) params.set(key, value);
	}
	params.set('limit', PAGE_SIZE);
	for (const [key, value] of Object.entries(extra)) params.set(key, value);
	return params.toString();
}

// ---- Loading -------------------------------------------------------------------------

/** Loads the first page again (new filters, or the person asked for a refresh). */
export async function reloadList() {
	const mine = ++requestNumber;
	controller?.abort();
	controller = new AbortController();
	state.list.status = 'loading';
	state.list.updatesAvailable = false;
	renderList();
	try {
		const page = await api('GET', `/api/items?${queryString()}`, { signal: controller.signal });
		if (mine !== requestNumber) return;
		state.list = { items: page.items, total: page.total, nextCursor: page.nextCursor, status: 'idle', error: null, updatesAvailable: false };
		$('#list-pane').scrollTop = 0; // a new first page: start at the top, not wherever the old list was scrolled to
	}
	catch (e) {
		if (e.name === 'AbortError' || mine !== requestNumber) return;
		state.list.status = 'error';
		state.list.error = e.message;
	}
	renderList();
}

export async function loadMore() {
	const { nextCursor, status } = state.list;
	if (!nextCursor || status !== 'idle') return;
	const mine = requestNumber;
	state.list.status = 'loading-more';
	renderList();
	try {
		const page = await api('GET', `/api/items?${queryString({ cursor: nextCursor })}`);
		if (mine !== requestNumber) return; // the filters changed while we were loading
		// The list can shift between pages; never show the same item twice.
		const known = new Set(state.list.items.map((item) => item.id));
		state.list.items.push(...page.items.filter((item) => !known.has(item.id)));
		Object.assign(state.list, { total: page.total, nextCursor: page.nextCursor });
	}
	catch (e) {
		if (mine === requestNumber) toast(e.message, true);
	}
	if (mine === requestNumber) state.list.status = 'idle';
	renderList();
}

const signature = (items) => items.map((item) => `${item.id}:${item.version}:${item.updatedAt}`).join(',');

/**
 * The periodic refresh. If the person is looking at the first page, it just updates. If
 * they have scrolled further, moving rows under them would be confusing, so we only say
 * that there are updates and let them choose.
 */
export async function pollList() {
	if (state.list.status !== 'idle') return;
	const mine = requestNumber;
	const page = await api('GET', `/api/items?${queryString()}`);
	if (mine !== requestNumber || state.list.status !== 'idle') return;

	const scrolledFurther = state.list.items.length > PAGE_SIZE;
	const sameRows = signature(page.items) === signature(state.list.items.slice(0, PAGE_SIZE));
	if (sameRows && page.total === state.list.total) return;
	if (scrolledFurther) {
		state.list.updatesAvailable = true;
		state.list.total = page.total;
	}
	else {
		state.list = { items: page.items, total: page.total, nextCursor: page.nextCursor, status: 'idle', error: null, updatesAvailable: false };
	}
	renderList();
}

// ---- Rendering -----------------------------------------------------------------------

export function renderList() {
	const list = $('#list');
	const { items, total, status, error, nextCursor, updatesAvailable } = state.list;
	list.setAttribute('aria-busy', String(status === 'loading'));
	renderCount();

	if (status === 'loading' && !items.length) {
		list.replaceChildren(...Array.from({ length: 7 }, skeletonRow));
		return;
	}
	if (status === 'error' && !items.length) {
		list.replaceChildren(el('li', { class: 'empty-state' },
			icon('alert', 28), el('strong', {}, "Couldn't load the list"), el('p', {}, error),
			el('button', { class: 'btn', type: 'button', onclick: reloadList }, 'Try again')));
		return;
	}
	if (!items.length) {
		list.replaceChildren(emptyState());
		return;
	}

	const rows = items.map(row);
	if (updatesAvailable) {
		rows.unshift(el('li', { class: 'updates' },
			el('button', { type: 'button', onclick: reloadList }, icon('refresh', 14), 'New activity. Refresh the list')));
	}
	if (nextCursor) {
		rows.push(el('li', { class: 'load-more', id: 'load-more' },
			el('button', { class: 'btn', type: 'button', disabled: status === 'loading-more', onclick: loadMore },
				status === 'loading-more' ? 'Loading…' : `Load more (${plural(total - items.length, 'item')} left)`)));
	}
	list.replaceChildren(...rows);
	watchEnd();
}

function renderCount() {
	const { items, total, status } = state.list;
	const text = status === 'loading' && !items.length ? 'Loading…'
		: total === 0 ? 'No items'
			: items.length < total ? `Showing ${items.length.toLocaleString()} of ${total.toLocaleString()}`
				: plural(total, 'item');
	$('#result-count').textContent = text;
}

/** Loads the next page when the "load more" row scrolls into view. The button still works. */
function watchEnd() {
	observer?.disconnect();
	const sentinel = $('#load-more');
	if (!sentinel || !('IntersectionObserver' in window)) return;
	observer = new IntersectionObserver((entries) => {
		if (entries.some((entry) => entry.isIntersecting)) loadMore();
	}, { root: $('#list-pane'), rootMargin: '200px' });
	observer.observe(sentinel);
}

const isActive = (item) => item.status !== 'closed' && item.status !== 'cancelled';

/** "Quiet 3 d": how long an unfinished item has gone without any activity. */
function quietFor(item) {
	const hours = (Date.now() - new Date(item.updatedAt).getTime()) / 3_600_000;
	return hours >= 24 ? `${Math.floor(hours / 24)} d` : `${Math.floor(hours)} h`;
}

function row(item) {
	const selected = item.id === state.selectedId;
	const staleHours = state.overview?.staleAfterHours ?? 24;
	const hoursQuiet = (Date.now() - new Date(item.updatedAt).getTime()) / 3_600_000;
	return el('li', { 'data-id': item.id },
		el('button', {
			type: 'button',
			class: `row${selected ? ' selected' : ''}`,
			'aria-current': selected ? 'true' : null,
			onclick: () => emit('open-item', item.id),
		},
			el('span', { class: `prio-bar ${item.priority}`, title: `${capitalize(item.priority)} priority` }),
			el('div', { class: 'row-main' },
				el('div', { class: 'row-title' }, item.title),
				el('div', { class: 'row-meta' },
					el('span', { class: 'chip' }, item.team.name),
					el('span', { class: `pill status-${item.status}` }, statusLabel(item.status)),
					item.priority === 'urgent' ? el('span', { class: 'tag danger' }, 'Urgent') : null,
					item.kind === 'incident' ? el('span', { class: 'tag danger-soft' }, 'Incident') : null,
					isActive(item) && item.requiresApproval && !item.approvedBy ? el('span', { class: 'tag warn' }, 'Needs approval') : null,
					isActive(item) && hoursQuiet > staleHours ? el('span', { class: 'tag muted' }, `Quiet ${quietFor(item)}`) : null)),
			el('div', { class: 'row-side' },
				avatar(item.assignee, 'sm'),
				when(item.updatedAt))));
}

function skeletonRow() {
	return el('li', { class: 'skeleton-row', 'aria-hidden': 'true' },
		el('span', { class: 'skeleton bar' }),
		el('div', {}, el('span', { class: 'skeleton line wide' }), el('span', { class: 'skeleton line' })));
}

function emptyState() {
	const filtered = Object.keys(DEFAULT_FILTERS).some((key) => state.filters[key] !== DEFAULT_FILTERS[key]);
	return el('li', { class: 'empty-state' },
		icon(filtered ? 'search' : 'inbox', 28),
		el('strong', {}, filtered ? 'Nothing matches these filters' : 'All clear'),
		el('p', {}, filtered ? 'Try a different search, or clear the filters to see everything.' : 'Nothing is open right now. New work will show up here.'),
		filtered ? el('button', { class: 'btn', type: 'button', onclick: () => $('#clear-filters').click() }, 'Clear filters') : null);
}

/** Highlights the open item without rebuilding the list (so scroll position and focus stay put). */
export function markSelected() {
	for (const button of $$('#list .row')) {
		const selected = Number(button.parentElement.dataset.id) === state.selectedId;
		button.classList.toggle('selected', selected);
		if (selected) button.setAttribute('aria-current', 'true');
		else button.removeAttribute('aria-current');
	}
}

/** The open item was just re-read from the server: make its row match, wherever it is in the list. */
export function syncItem(fresh) {
	const at = state.list.items.findIndex((item) => item.id === fresh.id);
	if (at < 0 || state.list.items[at].version === fresh.version && state.list.items[at].updatedAt === fresh.updatedAt) return;
	state.list.items[at] = fresh;
	renderList();
}

/** Shows an optimistic change in the list right away, before the server has answered. */
export function patchListItem(id, changes) {
	const item = state.list.items.find((entry) => entry.id === id);
	if (!item) return;
	Object.assign(item, changes);
	renderList();
}

/** Moves the selection up or down the list (keyboard shortcuts j and k). */
export function step(direction) {
	const items = state.list.items;
	if (!items.length) return;
	const at = items.findIndex((item) => item.id === state.selectedId);
	const next = items[Math.min(items.length - 1, Math.max(0, at + direction))];
	if (next) {
		emit('open-item', next.id);
		$(`#list li[data-id="${next.id}"] .row`)?.scrollIntoView({ block: 'nearest' });
	}
}
