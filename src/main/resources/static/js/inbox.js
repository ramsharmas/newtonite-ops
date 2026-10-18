// The bell: what other people did to the items I raised or own. The server fills this
// from a background job, so it can lag a second or two behind the action.

import { api } from './api.js';
import { emit, state } from './state.js';
import { $, el, icon, when } from './dom.js';

export function initInbox() {
	$('#bell').addEventListener('click', () => toggleInbox());
	$('#mark-read').addEventListener('click', async () => {
		state.inbox = await api('POST', '/api/notifications/read');
		renderInbox();
	});
	document.addEventListener('click', (event) => {
		if (!event.target.closest('.inbox-wrap')) toggleInbox(false);
	});
}

export function toggleInbox(open = $('#inbox').hidden) {
	$('#inbox').hidden = !open;
	$('#bell').setAttribute('aria-expanded', String(open));
}

export async function refreshInbox() {
	const fresh = await api('GET', '/api/notifications');
	if (JSON.stringify(fresh) === JSON.stringify(state.inbox)) return;
	state.inbox = fresh;
	renderInbox();
}

export function renderInbox() {
	const { unread, notifications } = state.inbox;
	const badge = $('#unread');
	badge.hidden = unread === 0;
	badge.textContent = unread > 9 ? '9+' : unread;
	$('#bell').setAttribute('aria-label', unread ? `Notifications, ${unread} unread` : 'Notifications');
	$('#mark-read').hidden = unread === 0;
	document.title = unread ? `(${unread > 9 ? '9+' : unread}) Newtonite Ops` : 'Newtonite Ops';

	const list = $('#inbox-list');
	if (!notifications.length) {
		list.replaceChildren(el('li', { class: 'empty-state small' }, icon('bell', 22), el('p', {}, 'Nothing yet. You will see changes to items you raised or own here.')));
		return;
	}
	list.replaceChildren(...notifications.map((n) =>
		el('li', { class: n.read ? '' : 'unread' },
			el('button', {
				type: 'button',
				onclick: () => {
					toggleInbox(false);
					emit('open-item', n.itemId);
				},
			}, el('span', { class: 'dot' }), el('span', { class: 'note-text' }, n.message, when(n.createdAt))))));
}
