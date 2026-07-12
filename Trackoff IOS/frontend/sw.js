// Minimal app-shell cache — only the static UI files, never API responses
// (those need to stay live). Lets the app open instantly on a flaky
// connection; actual data (library, previews, play counts) still needs
// the backend reachable.
const CACHE = 'trackoff-shell-v1';
const SHELL = [
  '/', '/index.html', '/styles.css', '/app.js', '/core.js', '/state.js', '/chooser.js', '/ranker.js',
  '/views/main.js', '/views/connect.js', '/views/library.js', '/views/csv-import.js',
  '/views/rank.js', '/views/swipe.js', '/views/tierlist.js', '/views/lastfm-manager.js',
  '/manifest.webmanifest', '/icons/icon-192.png', '/icons/icon-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
  );
  self.clients.claim();
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.pathname.startsWith('/api/')) return; // never cache API calls
  e.respondWith(
    caches.match(e.request).then((cached) => cached || fetch(e.request))
  );
});
