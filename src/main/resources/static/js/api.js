// The only place that talks to the server. Everything else calls api().

const TOKEN_KEY = 'token';

// sessionStorage, not localStorage, so two browser tabs can be signed in as different people.
// Every access is wrapped because storage can be blocked (private windows, strict settings).
export const session = {
	get token() {
		try {
			return sessionStorage.getItem(TOKEN_KEY);
		}
		catch {
			return memoryToken;
		}
	},
	set(token) {
		memoryToken = token;
		try {
			sessionStorage.setItem(TOKEN_KEY, token);
		}
		catch { /* the in-memory copy still works for this page */ }
	},
	clear() {
		memoryToken = null;
		try {
			sessionStorage.removeItem(TOKEN_KEY);
		}
		catch { /* nothing to clear */ }
	},
};
let memoryToken = null;

/** status 0 means the request never reached the server. */
export class ApiError extends Error {
	constructor(status, body) {
		super(body.message || `Request failed (${status})`);
		this.status = status;
		this.body = body;
	}
	get code() {
		return this.body.error;
	}
}

let onSignedOut = () => {};
export const whenSignedOut = (fn) => { onSignedOut = fn; };

export async function api(method, path, { body, headers = {}, signal } = {}) {
	const token = session.token;
	let response;
	try {
		response = await fetch(path, {
			method,
			signal,
			headers: {
				'Content-Type': 'application/json',
				...(token ? { Authorization: `Bearer ${token}` } : {}),
				...headers,
			},
			body: body === undefined ? undefined : JSON.stringify(body),
		});
	}
	catch (e) {
		if (e.name === 'AbortError') throw e;
		throw new ApiError(0, { error: 'network', message: "Can't reach the server. Check your connection and try again." });
	}
	const text = await response.text();
	let data = {};
	try {
		data = text ? JSON.parse(text) : {};
	}
	catch {
		data = { message: text.slice(0, 200) };
	}
	if (response.status === 401 && token) onSignedOut();
	if (!response.ok) throw new ApiError(response.status, data);
	return data;
}
