// Small helpers shared by every screen: a DOM builder, icons, avatars, time formatting,
// toasts and a confirm dialog.
//
// Nothing from the API is ever parsed as HTML. `el()` only creates text nodes from data;
// the only innerHTML below is for the fixed icon paths defined in this file.

export const $ = (selector, root = document) => root.querySelector(selector);
export const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

export function el(tag, attrs = {}, ...children) {
	const node = document.createElement(tag);
	for (const [key, value] of Object.entries(attrs)) {
		if (value == null || value === false) continue;
		if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
		else if (key === 'class') node.className = value;
		else if (key === 'value') node.value = value;
		else node.setAttribute(key, value === true ? '' : value);
	}
	for (const child of children.flat(Infinity)) {
		if (child == null || child === false) continue;
		node.append(child instanceof Node ? child : document.createTextNode(String(child)));
	}
	return node;
}

// ---- Icons ---------------------------------------------------------------------------

const ICONS = {
	search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
	plus: '<path d="M12 5v14M5 12h14"/>',
	bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
	back: '<path d="m15 18-6-6 6-6"/>',
	check: '<path d="M20 6 9 17l-5-5"/>',
	x: '<path d="M18 6 6 18M6 6l12 12"/>',
	clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
	user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
	alert: '<path d="M12 9v4M12 17h.01"/><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/>',
	shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="m9 12 2 2 4-4"/>',
	message: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
	refresh: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
	sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
	moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
	filter: '<path d="M3 5h18l-7 8v6l-4 2v-8z"/>',
	logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5M21 12H9"/>',
	edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
	userPlus: '<circle cx="9" cy="8" r="4"/><path d="M2 21a7 7 0 0 1 14 0M19 8v6M22 11h-6"/>',
	userMinus: '<circle cx="9" cy="8" r="4"/><path d="M2 21a7 7 0 0 1 14 0M16 11h6"/>',
	flag: '<path d="M4 22V4M4 4h13l-2 4 2 4H4"/>',
	inbox: '<path d="M22 12h-6l-2 3h-4l-2-3H2"/><path d="M5.5 5h13L22 12v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6z"/>',
};

export function icon(name, size = 16) {
	const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
	svg.setAttribute('viewBox', '0 0 24 24');
	svg.setAttribute('width', size);
	svg.setAttribute('height', size);
	svg.setAttribute('fill', 'none');
	svg.setAttribute('stroke', 'currentColor');
	svg.setAttribute('stroke-width', '2');
	svg.setAttribute('stroke-linecap', 'round');
	svg.setAttribute('stroke-linejoin', 'round');
	svg.setAttribute('aria-hidden', 'true');
	svg.classList.add('icon');
	svg.innerHTML = ICONS[name] || '';
	return svg;
}

// ---- People --------------------------------------------------------------------------

export function initials(name) {
	return name.split(/\s+/).filter(Boolean).map((part) => part[0]).join('').slice(0, 2).toUpperCase();
}

/** A round badge with the person's initials. The colour is stable for a given name. */
export function avatar(person, size = 'md') {
	if (!person) {
		return el('span', { class: `avatar ${size} empty`, title: 'Nobody yet' }, icon('user', size === 'sm' ? 12 : 14));
	}
	let hash = 0;
	for (const ch of person.name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
	return el('span', { class: `avatar ${size} av-${hash % 6}`, title: person.name }, initials(person.name));
}

// ---- Text and time -------------------------------------------------------------------

export const capitalize = (text) => text.charAt(0).toUpperCase() + text.slice(1);
export const statusLabel = (status) => capitalize(status.replace('_', ' '));
export const plural = (n, one, many = `${one}s`) => `${n.toLocaleString()} ${n === 1 ? one : many}`;

export function ago(iso) {
	const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
	if (seconds < 60) return 'just now';
	if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
	if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
	if (seconds < 86400 * 30) return `${Math.floor(seconds / 86400)} d ago`;
	return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export const fullTime = (iso) => new Date(iso).toLocaleString();

/** A <time> element: relative text, exact time on hover. */
export const when = (iso) => el('time', { datetime: iso, title: fullTime(iso) }, ago(iso));

export function debounce(fn, ms) {
	let timer;
	return (...args) => {
		clearTimeout(timer);
		timer = setTimeout(() => fn(...args), ms);
	};
}

/** crypto.randomUUID() only exists on https and localhost; this also works on a plain-http LAN address. */
export function uuid() {
	if (globalThis.crypto && crypto.randomUUID) return crypto.randomUUID();
	const bytes = new Uint8Array(16);
	(globalThis.crypto || { getRandomValues: (a) => a.map(() => Math.random() * 256) }).getRandomValues(bytes);
	bytes[6] = (bytes[6] & 0x0f) | 0x40;
	bytes[8] = (bytes[8] & 0x3f) | 0x80;
	const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
	return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

// ---- Feedback ------------------------------------------------------------------------

export function toast(message, isError = false) {
	const note = el('div', {
		class: `toast${isError ? ' error' : ''}`,
		role: isError ? 'alert' : 'status',
		onclick: () => note.remove(),
	}, icon(isError ? 'alert' : 'check', 16), el('span', {}, message));
	$('#toasts').append(note);
	setTimeout(() => note.remove(), isError ? 6000 : 2800);
}

/** Asks "are you sure?" in a dialog. Resolves to true or false. */
export function confirmDialog({ title, message, confirmLabel = 'Confirm', danger = false }) {
	const dialog = $('#confirm');
	$('#confirm-title').textContent = title;
	$('#confirm-message').textContent = message;
	const ok = $('#confirm-ok');
	ok.textContent = confirmLabel;
	ok.className = `btn ${danger ? 'danger' : 'primary'}`;
	return new Promise((resolve) => {
		const finish = (result) => {
			dialog.removeEventListener('close', onClose);
			resolve(result);
		};
		const onClose = () => finish(dialog.returnValue === 'ok');
		dialog.addEventListener('close', onClose);
		dialog.returnValue = '';
		dialog.showModal();
		ok.focus();
	});
}
