// Starts the page and keeps it fresh. Each screen lives in its own file; this one wires
// them together: sign in/out, the address bar (#/item/12), the 5-second refresh, shortcuts.

import { api, session, whenSignedOut } from './api.js';
import { POLL_MS, on, resetState, state } from './state.js';
import { $, avatar, el, toast } from './dom.js';
import { showSignIn } from './signin.js';
import { initToolbar, markSelected, pollList, reloadList, resetToolbar, step } from './list.js';
import { refreshOverview, renderOverview } from './overview.js';
import { initInbox, refreshInbox, renderInbox, toggleInbox } from './inbox.js';
import { initNewItem, openNewItem } from './newitem.js';
import { closeItem, loadDetail, openItem, pollDetail, renderDetail } from './detail.js';

let timer = null;

// ---- Sign in and out -----------------------------------------------------------------

async function signIn(userId) {
	try {
		const result = await api('POST', '/api/login', { body: { userId } });
		session.set(result.token);
		await start();
	}
	catch (e) {
		toast(e.message, true);
	}
}

async function signOut() {
	try {
		await api('POST', '/api/logout');
	}
	catch { /* signing out locally is enough */ }
	signedOut();
}

function signedOut(message = '') {
	clearInterval(timer);
	session.clear();
	resetState();
	closeMenus();
	history.replaceState(null, '', location.pathname + location.search);
	showSignIn(signIn, message);
}

async function start() {
	try {
		state.me = await api('GET', '/api/me');
	}
	catch (e) {
		signedOut(e.status === 401 ? 'Your session ended. Please sign in again.' : e.message);
		return;
	}
	$('#signin').hidden = true;
	$('#app').hidden = false;
	renderUser();
	resetToolbar();
	renderOverview();
	renderInbox();
	renderDetail();
	await Promise.allSettled([reloadList(), refreshOverview(), refreshInbox()]);
	await showItemFromAddress();
	clearInterval(timer);
	timer = setInterval(poll, POLL_MS);
}

function renderUser() {
	$('#user-avatar').replaceChildren(avatar(state.me, 'md'));
	$('#user-name').textContent = state.me.name;
	$('#menu-name').textContent = state.me.name;
	$('#menu-teams').replaceChildren(...state.me.teams.map((team) => el('span', { class: `chip ${team.role}` }, `${team.name} · ${team.role}`)));
}

// ---- The address bar -----------------------------------------------------------------
// #/item/12 opens item 12. That makes links shareable and lets the browser's back button
// (and the phone's) go from an item back to the list.

const idInAddress = () => {
	const match = location.hash.match(/^#\/item\/(\d+)$/);
	return match ? Number(match[1]) : null;
};

async function showItemFromAddress() {
	if (!state.me) return;
	const id = idInAddress();
	if (id === state.selectedId) return;
	if (id == null) {
		closeItem();
	}
	else {
		const opening = openItem(id);
		markSelected();
		await opening;
	}
	markSelected();
}

function go(id) {
	if (id === state.selectedId) {
		if (id != null) loadDetail(id);
		return;
	}
	const base = location.pathname + location.search;
	history.pushState(null, '', id == null ? base : `${base}#/item/${id}`);
	showItemFromAddress();
}

// ---- Keeping the page fresh ----------------------------------------------------------
// Polling, not push: simple, and fine for a team-sized app. See ENGINEERING_DECISIONS.md.

async function poll() {
	if (document.hidden || !state.me) return;
	const results = await Promise.allSettled([refreshOverview(), refreshInbox(), pollList(), pollDetail()]);
	// status 0 = the request never reached the server (network down, server stopped).
	setOffline(results.some((result) => result.status === 'rejected' && result.reason && result.reason.status === 0));
}

function setOffline(offline) {
	if (state.offline === offline) return;
	state.offline = offline;
	$('#offline').hidden = !offline;
}

// ---- Menus, theme, shortcuts ---------------------------------------------------------

function closeMenus() {
	toggleInbox(false);
	$('#user-menu').hidden = true;
	$('#user-button').setAttribute('aria-expanded', 'false');
}

function applyTheme(theme) {
	document.documentElement.dataset.theme = theme;
	try {
		localStorage.setItem('theme', theme);
	}
	catch { /* the choice just won't be remembered */ }
}

function toggleTheme() {
	const dark = document.documentElement.dataset.theme
		? document.documentElement.dataset.theme === 'dark'
		: matchMedia('(prefers-color-scheme: dark)').matches;
	applyTheme(dark ? 'light' : 'dark');
}

function wireOnce() {
	initToolbar();
	initInbox();
	initNewItem();

	$('#user-button').addEventListener('click', () => {
		const open = $('#user-menu').hidden;
		closeMenus();
		$('#user-menu').hidden = !open;
		$('#user-button').setAttribute('aria-expanded', String(open));
	});
	document.addEventListener('click', (event) => {
		if (!event.target.closest('.user-wrap')) {
			$('#user-menu').hidden = true;
			$('#user-button').setAttribute('aria-expanded', 'false');
		}
	});
	$('#theme-toggle').addEventListener('click', toggleTheme);
	$('#sign-out').addEventListener('click', signOut);

	on('open-item', go);
	on('close-item', () => go(null));
	on('items-changed', () => {
		pollList().catch(() => {});
		refreshOverview().catch(() => {});
	});
	on('filters-changed', () => {
		renderOverview();
		refreshOverview().catch(() => {});
	});
	on('item-closed', () => {
		if (idInAddress() != null) history.replaceState(null, '', location.pathname + location.search);
		markSelected();
	});
	window.addEventListener('popstate', showItemFromAddress);
	document.addEventListener('visibilitychange', () => {
		if (!document.hidden) poll();
	});

	document.addEventListener('keydown', (event) => {
		if (!state.me) return;
		if (event.key === 'Escape') {
			closeMenus();
			if (document.activeElement === $('#search')) $('#search').blur();
			return;
		}
		const typing = event.target.closest('input, textarea, select, [contenteditable]');
		if (typing || event.metaKey || event.ctrlKey || event.altKey || document.querySelector('dialog[open]')) return;
		if (event.key === '/') {
			event.preventDefault();
			$('#search').focus();
		}
		else if (event.key === 'n') {
			event.preventDefault();
			openNewItem();
		}
		else if (event.key === 'j') step(1);
		else if (event.key === 'k') step(-1);
	});

	whenSignedOut(() => {
		if (state.me) signedOut('Your session ended. Please sign in again.');
	});
}

wireOnce();

if (session.token) start();
else showSignIn(signIn);
