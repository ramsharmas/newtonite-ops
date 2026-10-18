// What the page currently knows. One object, read by the render functions.
// The server stays the source of truth: this is only a copy that is refreshed often.

export const PAGE_SIZE = 30;
export const POLL_MS = 5000;

export const DEFAULT_FILTERS = Object.freeze({
	q: '', teamId: '', status: 'active', assigned: '', priority: '', attention: '', sort: 'updated',
});

export const state = {
	me: null,
	filters: { ...DEFAULT_FILTERS },
	list: { items: [], total: 0, nextCursor: null, status: 'loading', error: null, updatesAvailable: false },
	overview: null,
	selectedId: null,
	detail: null, // the open item as the server last told us (plus optimistic changes while saving)
	draft: null, // { itemId, title, description, priority }: what this person typed but hasn't saved
	conflict: null, // { current, mine } after a refused save
	staleNote: false, // the open item changed on the server while this person has unsaved edits
	commentDrafts: new Map(), // itemId -> text typed but not sent
	pendingComments: new Map(), // itemId -> [{ key, body, status: 'sending' | 'failed', error }]
	inbox: { unread: 0, notifications: [] },
	offline: false,
};

// A tiny event bus so screens can react to each other without importing each other.
const bus = new EventTarget();
export const emit = (name, detail) => bus.dispatchEvent(new CustomEvent(name, { detail }));
export const on = (name, handler) => bus.addEventListener(name, (event) => handler(event.detail));

export function resetState() {
	Object.assign(state, {
		me: null,
		filters: { ...DEFAULT_FILTERS },
		list: { items: [], total: 0, nextCursor: null, status: 'loading', error: null, updatesAvailable: false },
		overview: null,
		selectedId: null,
		detail: null,
		draft: null,
		conflict: null,
		staleNote: false,
		commentDrafts: new Map(),
		pendingComments: new Map(),
		inbox: { unread: 0, notifications: [] },
		offline: false,
	});
}
