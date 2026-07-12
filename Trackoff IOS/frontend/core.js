// Shared utilities: fetch wrappers, toast, simple hash router, one shared
// <audio> element (mirrors the desktop app's "only one preview plays at a
// time" rule), and a couple of DOM helpers used by every view.

export const app = document.getElementById('app');

// ---------------------------------------------------------------------
// API
// ---------------------------------------------------------------------
export async function apiGet(path) {
  const res = await fetch(path);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

export async function apiPost(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

// ---------------------------------------------------------------------
// Toast
// ---------------------------------------------------------------------
export function toast(message, isError = false) {
  const el = document.createElement('div');
  el.className = 'toast' + (isError ? ' error' : '');
  el.textContent = message;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), isError ? 4000 : 2400);
}

// ---------------------------------------------------------------------
// Router — tiny hash-based SPA router with a JS-object route table.
// Each view module exports a `render(container, params)` function.
// ---------------------------------------------------------------------
const routes = {};
export function registerRoute(name, renderFn) { routes[name] = renderFn; }

export function nav(name, params = {}) {
  const query = new URLSearchParams(params).toString();
  window.location.hash = `#${name}${query ? '?' + query : ''}`;
}

export function back() { window.history.back(); }

function currentRoute() {
  const hash = window.location.hash.replace(/^#/, '') || 'main';
  const [name, query] = hash.split('?');
  const params = Object.fromEntries(new URLSearchParams(query || ''));
  return { name, params };
}

export function startRouter() {
  const dispatch = () => {
    stopAudio(); // never let a preview keep playing across a navigation
    const { name, params } = currentRoute();
    const fn = routes[name] || routes['main'];
    app.innerHTML = '';
    fn(app, params);
    window.scrollTo(0, 0);
  };
  window.addEventListener('hashchange', dispatch);
  dispatch();
}

// ---------------------------------------------------------------------
// Shared audio — one <audio> element for the whole app, so starting a
// new preview always stops whatever was playing before, everywhere.
// ---------------------------------------------------------------------
const audioEl = new Audio();
let onAudioEnd = null;

export function playPreview(url, onEnded) {
  stopAudio();
  if (!url) return false;
  audioEl.src = url;
  audioEl.volume = 0.6;
  onAudioEnd = onEnded || null;
  audioEl.play().catch(() => {});
  return true;
}

export function pausePreview() { audioEl.pause(); }
export function resumePreview() { audioEl.play().catch(() => {}); }
export function isAudioPlaying() { return !audioEl.paused && audioEl.currentTime > 0; }
export function stopAudio() {
  audioEl.pause();
  audioEl.removeAttribute('src');
  onAudioEnd = null;
}
audioEl.addEventListener('ended', () => { if (onAudioEnd) onAudioEnd(); });

// ---------------------------------------------------------------------
// DOM helpers
// ---------------------------------------------------------------------
export function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k === 'html') node.innerHTML = v;
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined) node.setAttribute(k, v);
  }
  for (const c of [].concat(children)) {
    if (c === null || c === undefined) continue;
    node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
  }
  return node;
}

export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function header(title, opts = {}) {
  const h = el('div', { class: 'header' }, [
    opts.onBack ? el('button', { class: 'back-btn', onclick: opts.onBack }, '‹ Back') : null,
    el('h1', {}, title),
    el('div', { class: 'spacer' }),
    ...(opts.right || []),
  ]);
  return h;
}

export function spinner() { return el('div', { class: 'spinner' }); }
