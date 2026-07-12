# Trackoff iOS

A mobile-web companion to the Trackoff desktop app — everything (Library,
Rank, Swipe, Tier List, Last.fm Manager) rebuilt as a phone-friendly PWA
you run from a browser and can add to your home screen. It's a full
rewrite (Java backend + vanilla JS frontend, no frameworks/build step),
not the desktop app itself — JavaFX doesn't run on iOS.

**Nothing outside this folder was touched.** The desktop app in the
parent `Trackoff/` directory is untouched and works exactly as before.

## Why it's built this way

- **No Node/npm on this machine**, so the backend is plain Java (built-in
  `com.sun.net.httpserver`, no framework) — same toolchain as the desktop
  app, and it let me directly reuse the already-debugged Spotify/Last.fm/
  Deezer logic from the desktop app (rate limiting, retry logic, the
  search-first Last.fm matching strategy, etc.) instead of re-discovering
  the same bugs in a rewrite.
- **The frontend is a mobile-first PWA**, not native Swift — I have no Mac/
  Xcode in this environment, so native iOS code would have been written
  completely blind with zero ability to compile or test it. This way,
  everything was actually built and verified end-to-end before you saw it.
- **One process serves both the API and the static frontend**, so your
  phone's browser never makes a cross-origin call — no CORS issues, and
  your Spotify/Last.fm credentials never reach client-side JS.

## Running it

```powershell
cd "Trackoff IOS\backend"
.\compile.ps1
.\run.ps1
```

Then open **http://localhost:8080** on this PC to try it locally.

The first run copies your Spotify Client ID/Secret and Last.fm username/
API key from the desktop app's local database automatically, if it finds
one — nothing is sent anywhere, it's a local file copy on this same
machine. You can also just enter them fresh in Connect Spotify / Connect
Last.fm inside the app.

## Using it from your phone

1. Make sure your phone and this PC are on the **same Wi-Fi network**.
2. Find this PC's local IP address: open PowerShell and run
   `ipconfig` — look for the "IPv4 Address" under your Wi-Fi adapter
   (something like `10.x.x.x` or `192.168.x.x`). As of this build it was
   `10.9.181.3`, but it can change — check yours.
3. On your phone's browser, go to `http://<that-IP>:8080`.
4. Tap the Share button → **Add to Home Screen** for an app-like icon
   and full-screen experience (no browser chrome).
5. **Leave `run.ps1` running on this PC** — the app needs it; there's no
   separate server to deploy anywhere.

## The Spotify login caveat

This is the one piece I couldn't fully verify myself (I won't enter your
Spotify password) and it needs one manual setup step:

Spotify only allows a small, exact-match list of OAuth redirect URIs per
app. The desktop app is registered for its own loopback ports; this app
needs its own entries added at
**[developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) → your app → Settings → Redirect URIs**:

- `http://127.0.0.1:8080/api/spotify/callback` — for using it from this PC
- `http://<your-phone-access-IP>:8080/api/spotify/callback` — for using it from your phone (must match exactly, including the IP)

Spotify requires plain `http://` redirects to be `localhost`/`127.0.0.1`
specifically — a LAN IP like `10.9.181.3` is **not** treated as loopback,
and Spotify's security rules mean it may still reject a non-HTTPS,
non-loopback redirect even once added to the dashboard. If logging in
from your phone doesn't complete after adding the redirect URI, the
reliable fallback is: **log in once from this PC** (`http://localhost:8080`,
which Spotify does allow over plain HTTP) — the resulting login is saved
server-side and your phone will just see "already connected," no login
needed there.

Last.fm and Deezer don't have this problem — no OAuth, so they work
identically from the PC or the phone once connected.

## What's here vs. what's different from the desktop app

**All ported over:**
- Connect Spotify / Connect Last.fm
- Library — browse playlists, pick a destination
- Rank — same adaptive merge-sort algorithm, pairwise comparisons
- Swipe — touch drag gestures (not mouse drag), keep/delete/undo, preview
  playback with next-card preloading
- Tier List — touch-based drag and drop (not HTML5 drag-and-drop, which
  doesn't work reliably on touchscreens) for both moving songs between
  tiers and reordering tiers themselves; create/delete/rename tiers
- Last.fm Manager — play-count bars, sort by most played, manual
  reassignment (long-press a row instead of right-click)
- CSV import

**Left out / different on purpose:**
- No "Write to Spotify" delete button — the desktop app already
  confirmed Spotify blocks playlist-modify writes for personal/
  unreviewed apps regardless of granted scope, so it wasn't ported here
  either.
- No PNG tier-list export (desktop-only AWT/Swing capability).
- Session save/resume-to-file isn't implemented — a ranking or swipe
  session lives only as long as the browser tab does. Worth adding later
  if you want it (localStorage would be the natural fit).
- No keyboard shortcuts (arrow keys) — touch gestures/taps only, since
  that's the primary input on a phone.

## Known rough edges

- The clickable list rows/tiles are plain `<div>`s with click handlers,
  not semantic `<button>` elements — they work fine for touch/mouse but
  aren't ideal for screen readers or keyboard navigation. Not fixed due
  to time; would need touching every view file.
- Only tested against a real device via a desktop browser resized to a
  phone viewport (375×812) — the touch drag-and-drop logic was verified
  by simulating pointer events programmatically, not with an actual
  finger on actual glass. Please double check Tier List drag-and-drop
  and Swipe gestures feel right on your actual phone.
