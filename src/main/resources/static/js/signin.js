// The sign-in screen. It is a demo picker with no passwords: real sign-in (SSO) would
// replace this screen and the server's AuthController, and nothing else would change.

import { api } from './api.js';
import { $, avatar, el, icon } from './dom.js';

export async function showSignIn(onPick, message = '') {
	$('#app').hidden = true;
	$('#signin').hidden = false;
	const notice = $('#signin-notice');
	notice.textContent = message;
	notice.hidden = !message;
	const box = $('#people');
	box.replaceChildren(...Array.from({ length: 6 }, () => el('div', { class: 'person skeleton-person' }, el('span', { class: 'skeleton line wide' }))));
	try {
		const people = await api('GET', '/api/users');
		box.replaceChildren(...people.map((person) =>
			el('button', { class: 'person', type: 'button', onclick: () => onPick(person.id) },
				avatar(person, 'lg'),
				el('span', { class: 'person-text' },
					el('strong', {}, person.name),
					el('span', { class: 'person-teams' }, person.teams.map((team) =>
						el('span', { class: `chip ${team.role}` }, `${team.name} · ${team.role}`)))))));
	}
	catch (e) {
		box.replaceChildren(el('div', { class: 'empty-state' },
			icon('alert', 28), el('strong', {}, "Can't reach the server"), el('p', {}, e.message),
			el('button', { class: 'btn', type: 'button', onclick: () => showSignIn(onPick, message) }, 'Try again')));
	}
}
