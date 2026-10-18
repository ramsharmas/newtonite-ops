// The cards at the top: what needs attention right now. Each card is a shortcut to a
// filter, and its number is exactly how many items that filter returns.

import { api } from './api.js';
import { state } from './state.js';
import { $, el, icon } from './dom.js';
import { setFilters } from './list.js';

const BASE = { status: 'active', assigned: '', priority: '', attention: '' };

function cards(staleHours) {
	return [
		{ key: 'active', field: 'active', label: 'All active', hint: 'Not closed or cancelled', preset: {} },
		{ key: 'mine', field: 'mine', label: 'Assigned to me', hint: 'Yours to move forward', preset: { assigned: 'me' } },
		{ key: 'unassigned', field: 'unassigned', label: 'Needs an owner', hint: 'Nobody has taken it yet', preset: { assigned: 'unassigned' }, tone: 'attention' },
		{ key: 'urgent', field: 'urgent', label: 'Urgent', hint: 'Highest priority', preset: { priority: 'urgent' }, tone: 'danger' },
		{ key: 'approval', field: 'needsApproval', label: 'Waiting for approval', hint: 'A lead has to sign off', preset: { attention: 'approval' }, tone: 'attention' },
		{ key: 'stale', field: 'stale', label: 'Gone quiet', hint: `No activity for ${staleHours}h or more`, preset: { attention: 'stale' }, tone: 'muted' },
	];
}

const isActive = (card) => Object.entries({ ...BASE, ...card.preset }).every(([key, value]) => state.filters[key] === value);

export async function refreshOverview() {
	const params = new URLSearchParams();
	if (state.filters.teamId) params.set('teamId', state.filters.teamId);
	const fresh = await api('GET', `/api/items/overview?${params}`);
	if (JSON.stringify(fresh) === JSON.stringify(state.overview)) return;
	state.overview = fresh;
	renderOverview();
}

export function renderOverview() {
	const overview = state.overview;
	$('#overview').replaceChildren(...cards(overview?.staleAfterHours ?? 24).map((card) => {
		const active = isActive(card);
		const count = overview ? overview[card.field] : null;
		return el('button', {
			type: 'button',
			class: `stat ${card.tone || ''}${active ? ' active' : ''}${count === 0 ? ' zero' : ''}`,
			'aria-pressed': String(active),
			title: card.hint,
			// Clicking the card that is already selected goes back to "all active".
			onclick: () => setFilters({ ...BASE, ...(active ? {} : card.preset) }),
		},
			el('span', { class: 'stat-number' }, count == null ? '–' : count.toLocaleString()),
			el('span', { class: 'stat-label' }, card.label),
			el('span', { class: 'stat-hint' }, card.hint),
			card.tone === 'danger' && count > 0 ? icon('alert', 14) : null);
	}));
}
