// The "New item" dialog.

import { api } from './api.js';
import { emit, state } from './state.js';
import { $, el, toast, uuid } from './dom.js';

// One key per time the dialog is opened. If the request is retried, or the button is
// clicked twice, the server sees the same key and creates the item only once.
let idempotencyKey = null;

export function initNewItem() {
	const dialog = $('#new-item');
	const form = $('#new-item-form');
	$('#new-item-button').addEventListener('click', openNewItem);
	$('#new-item-cancel').addEventListener('click', () => dialog.close());
	form.addEventListener('submit', createItem);
	form.elements.title.addEventListener('input', () => {
		$('#new-item-error').hidden = true;
	});
	form.addEventListener('keydown', (event) => {
		if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) form.requestSubmit();
	});
	// Clicking the dimmed area outside the form closes the dialog.
	dialog.addEventListener('mousedown', (event) => {
		if (event.target === dialog) dialog.close();
	});
}

export function openNewItem() {
	if (!state.me) return;
	const form = $('#new-item-form');
	form.reset();
	form.elements.teamId.replaceChildren(...state.me.teams.map((team) => el('option', { value: team.id }, team.name)));
	if (state.filters.teamId) form.elements.teamId.value = state.filters.teamId;
	$('#new-item-error').hidden = true;
	idempotencyKey = uuid();
	$('#new-item').showModal();
	form.elements.title.focus();
}

async function createItem(event) {
	event.preventDefault();
	const form = event.target;
	const fields = form.elements;
	const submit = $('#new-item-submit');
	submit.disabled = true;
	submit.textContent = 'Creating…';
	try {
		const item = await api('POST', '/api/items', {
			headers: { 'Idempotency-Key': idempotencyKey },
			body: {
				teamId: Number(fields.teamId.value),
				title: fields.title.value,
				description: fields.description.value,
				priority: fields.priority.value,
				kind: fields.kind.value,
				requiresApproval: fields.requiresApproval.checked,
			},
		});
		$('#new-item').close();
		toast('Item created');
		emit('items-changed');
		emit('open-item', item.id);
	}
	catch (e) {
		const box = $('#new-item-error');
		box.textContent = e.message;
		box.hidden = false;
	}
	finally {
		submit.disabled = false;
		submit.textContent = 'Create item';
	}
}
