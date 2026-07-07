# Trackoff — Phase 1: Playlist Manager Foundation

**Status:** In progress
**Scope:** Transform Trackoff from a pure pairwise-comparison ranker into a full local-first Spotify playlist manager, with optional Spotify sync and optional Last.fm scrobble linking.
**Guiding principle:** **Local-first.** Trackoff must be fully usable offline. Network features (Spotify sync, Last.fm play counts) are additive, never required.

---

## Why "local-first" is the design principle

Two forcing functions pushed this:

1. **Corporate network blocks** — `accounts.spotify.com`, `api.spotify.com`, and `ws.audioscrobbler.com` are all blocked on the primary development machine. This forced early confrontation with the "what happens when the network is unreachable?" question.
2. **User trust** — users shouldn't need a Spotify account, an internet connection, or third-party credentials to open the app and start managing music data. The ranker, tier list, and swipe view already work fully offline against CSV; the manager should too.

**Consequence:** every network feature ships behind a capability check. Every view degrades gracefully when Spotify/Last.fm are absent. The local SQLite database is the source of truth; Spotify is a sync source, not the storage layer.

---

## Phase 1 goals (in priority order)

1. **Persistent local library** — songs, playlists, and playlist ordering stored in SQLite, survive across app restarts
2. **CSV import** — bring in an existing library (e.g. exported from Spotify via a third-party tool) with zero authentication
3. **Library View** — browse playlists and songs in the app, independent of network availability
4. **Spotify OAuth (opt-in)** — authenticate when the user wants sync, cleanly no-op when they don't
5. **Spotify library sync (opt-in)** — pull playlists and songs from Spotify into the local DB
6. **Last.fm linking (opt-in)** — attach a Last.fm username + API key, fetch scrobble counts, enable "most listened" sort
7. **Settings view** — one place to manage connections, view data folder, reset state

Everything after goal #3 requires network. Goals #1–#3 are the "at-work-testable" core.

---

## Architecture at a glance

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer  (JavaFX)                                         │
│  MainView │ LibraryView │ RankerView │ TierListView │ ...  │
└──────────────────────┬──────────────────────────────────────┘
                       │  reads/writes via services
┌──────────────────────▼──────────────────────────────────────┐
│  Service Layer                                              │
│  LibraryService │ SyncService │ NetworkStatus │ Settings   │
└──────┬────────────────────┬─────────────────────┬───────────┘
       │                    │                     │
┌──────▼────────┐   ┌───────▼────────┐    ┌───────▼─────────┐
│  DAO Layer    │   │  Spotify Client│    │  Last.fm Client │
│  PlaylistDao  │   │  (opt-in)      │    │  (opt-in)       │
│  SongDao      │   └────────────────┘    └─────────────────┘
│  PlaylistSongs│                                             
└──────┬────────┘                                             
       │                                                       
┌──────▼──────────────────────────────────────────────────────┐
│  SQLite  (%USERPROFILE%\.trackoff\trackoff.db)              │
└─────────────────────────────────────────────────────────────┘
```

**Key rule:** UI never talks to network clients directly. It always goes through a service, which decides whether to hit the DB, the network, or both.

---

## Progress so far

### ✅ Drop 1 — Storage foundation (shipped, compiles, verified)

| File | Purpose |
|------|---------|
| `src/com/trackoff/config/AppPaths.java` | Central path constants — `%USERPROFILE%\.trackoff\`, `trackoff.db`, legacy `.rankify\spotify.txt` for migration |
| `src/com/trackoff/db/schema/V1__initial_schema.sql` | Full initial schema (see below) |
| `src/com/trackoff/db/Migrations.java` | Versioned SQL migration runner, hand-rolled statement splitter |
| `src/com/trackoff/db/Database.java` | SQLite singleton, WAL journal mode, `PRAGMA foreign_keys=ON` |
| `src/com/trackoff/db/Dao.java` | Thin JDBC helpers: `exec`, `queryOne`, `query` |
| `src/com/trackoff/config/Settings.java` | Key/value wrapper over the `settings` table |
| `compile.ps1` | Adds `sqlite-jdbc-3.53.2.0.jar` to classpath, copies non-Java resources into `out\` |
| `run.ps1` | Adds SQLite JAR, ensures data dir, native-access flags, quoted proxy prop |
| `Main.java` | Calls `Database.init()` at top of `start(Stage)` with error alert on failure |

**Schema (V1) tables:**
- `settings` — key/value app settings
- `oauth_tokens` — Spotify access + refresh tokens, expiry
- `spotify_user` — cached Spotify profile info
- `playlists` — playlist metadata (id, name, description, owner, is_local vs is_synced, updated_at)
- `songs` — song metadata (id, title, artist, album, duration_ms, spotify_id, isrc)
- `playlist_songs` — join table with `order_index` and `added_at` for stable ordering
- `playlist_snapshots` — for future "restore playlist to previous state" feature
- `lastfm_scrobbles` — cached scrobble counts per song
- `song_blocks` / `block_songs` — for future "grouping / batch operations" feature

### ✅ Drop 2 — Auth layer (shipped, compiles clean at 29 files)

| File | Purpose |
|------|---------|
| `src/com/trackoff/io/spotify/TokenStore.java` | `Tokens` record with `isFresh()` (60s slack), upsert/load/clear against `oauth_tokens` |
| `src/com/trackoff/io/spotify/OAuthCallbackServer.java` | `com.sun.net.httpserver.HttpServer` on ports 47821/47822/47823 (fallback), state validation, Spotify-green success page, `CompletableFuture<String>` for code delivery |
| `src/com/trackoff/io/spotify/SpotifyAuth.java` | Hand-rolled Authorization Code flow: `login()` (browser via `Desktop.browse` w/ `rundll32` fallback), `refresh()`, scopes: `playlist-read-private playlist-read-collaborative playlist-modify-public playlist-modify-private user-library-read` |

**Note:** This code is compile-verified but **not runtime-verified** because the dev network blocks `accounts.spotify.com` and `api.spotify.com`. Runtime testing must happen off-network.

---

## Remaining Phase 1 work

Ordered by "can I do this at work" and dependency.

### 🔲 Drop 3 — DAOs and CSV import (offline-testable)

**New files:**

```
src/com/trackoff/db/PlaylistDao.java
src/com/trackoff/db/SongDao.java
src/com/trackoff/db/PlaylistSongDao.java
src/com/trackoff/service/LibraryService.java
src/com/trackoff/io/csv/CsvLibraryImporter.java
```

**Responsibilities:**

- **`PlaylistDao`** — `insert(Playlist)`, `update(Playlist)`, `delete(String id)`, `findById(String id)`, `findAll()`, `findAllLocal()`, `findAllSynced()`
- **`SongDao`** — `upsert(Song)` (Spotify-ID as natural key when present), `findById(String id)`, `findByIds(List<String>)`, `search(String query)`
- **`PlaylistSongDao`** — `addSongToPlaylist(playlistId, songId, orderIndex)`, `removeSongFromPlaylist(...)`, `getSongsInPlaylist(playlistId)` ordered by `order_index`, `reorder(playlistId, List<songId>)`
- **`LibraryService`** — coarse-grained façade over the DAOs; the UI's only entry point. Handles transactions.
- **`CsvLibraryImporter`** — parse the existing `My Spotify Library.csv` format, insert into DB. Reuses the existing CSV parsing code from the ranker's Spotify source, refactored into a shared utility.

**Testing plan (all offline):**
1. Import `My Spotify Library.csv`
2. Query all playlists → verify count
3. Pick a playlist → query songs → verify order preserved
4. Restart app → same data present
5. Re-import same CSV → no duplicate songs (upsert works)

### 🔲 Drop 4 — Library View shell (offline-testable)

**New files:**

```
src/com/trackoff/ui/library/LibraryView.java
src/com/trackoff/ui/library/PlaylistListPane.java   (left sidebar)
src/com/trackoff/ui/library/SongTablePane.java      (main pane)
src/com/trackoff/ui/library/LibraryToolbar.java     (top bar)
```

**Layout:**
- **Left sidebar** — list of playlists, click to select, "+" button to create a new local playlist
- **Main pane** — `TableView<Song>` with columns: #, Title, Artist, Album, Duration, ⋯(actions)
- **Toolbar** — search box (filters current playlist's songs), sort dropdown, "Import CSV" button, "Sync from Spotify" button (disabled if not connected)

**Wired into `MainView`** as a new top-level mode alongside Ranker / Tier List / Swipe.

**Testing plan:**
1. Empty DB → shows welcome/empty state with "Import CSV" call-to-action
2. After import → sidebar populated, first playlist auto-selected, table populated
3. Click through playlists → table updates
4. Search box → filters visible rows
5. All of this with **zero network access**

### 🔲 Drop 5 — Network status + graceful degradation (offline-testable)

**New files:**

```
src/com/trackoff/net/NetworkStatus.java
src/com/trackoff/ui/common/OfflineBanner.java
```

**Behavior:**
- On startup, `NetworkStatus` does a lightweight reachability check (HEAD to `https://api.spotify.com` with 3s timeout)
- Exposes a JavaFX `BooleanProperty online`
- Views bind to it: sync buttons disabled when offline, subtle banner shown, no error dialogs on network-dependent actions
- Refreshable manually via a "retry" button in the offline banner

### 🔲 Drop 6 — Spotify library sync (**needs network — test at home**)

**New files:**

```
src/com/trackoff/io/spotify/SpotifyApi.java         (thin HTTP wrapper: GET /me, /me/playlists, /playlists/{id}/tracks)
src/com/trackoff/io/spotify/SpotifyJsonParser.java  (hand-rolled JSON — no libraries)
src/com/trackoff/service/SyncService.java           (orchestrates pull-down into DB)
src/com/trackoff/ui/library/SyncProgressDialog.java (modal with progress bar)
```

**Sync semantics:**
- Pull-only in Phase 1 (Spotify → local). Push (local → Spotify) is Phase 2.
- Playlists marked `is_synced = true` in DB, tied to `spotify_id`
- Locally-created playlists (`is_local = true`) are never overwritten by sync
- Full re-sync is safe and idempotent (upserts by Spotify ID)
- Progress reported per-playlist for user feedback

**Testing plan (at home):**
1. Fresh DB, connect Spotify, hit "Sync"
2. Verify all playlists appear in sidebar
3. Verify song order matches Spotify's order
4. Modify a playlist on Spotify, re-sync, verify local reflects change
5. Create a local playlist, re-sync, verify it isn't touched

### 🔲 Drop 7 — Last.fm linking (**needs network — test at home**)

**New files:**

```
src/com/trackoff/io/lastfm/LastFmClient.java
src/com/trackoff/io/lastfm/LastFmJsonParser.java
src/com/trackoff/service/ScrobbleService.java
src/com/trackoff/ui/settings/ConnectLastFmView.java   (already partially built)
```

**Behavior:**
- User enters Last.fm username + API key in settings
- "Test & save" validates via `user.getInfo` (already implemented, currently returning 403 due to network block)
- Once linked, `ScrobbleService.refreshCountsFor(List<Song>)` populates `lastfm_scrobbles` table
- Library view gains a "Plays" column and a "Most Listened" sort option
- Fully optional; hidden entirely if not linked

**Error handling improvement (do this now, in Drop 3 or Drop 5):**
When Last.fm returns 403, show:
> "Last.fm returned 403. This usually means either:
>  • Your network is blocking last.fm (common on corporate/school networks)
>  • The API key is invalid
>  • The User-Agent header is missing
> Try opening https://www.last.fm in a browser to confirm access."

### 🔲 Drop 8 — Settings view

**New files:**

```
src/com/trackoff/ui/settings/SettingsView.java
src/com/trackoff/ui/settings/ConnectSpotifyView.java   (already partially built)
```

**Contents:**
- Spotify: Connect / Disconnect, show connected username
- Last.fm: Connect / Disconnect, show username, show cached scrobble count
- Data folder: display path, "Open in Explorer" button
- Reset: "Clear all data" (with confirmation), "Reset Spotify auth only"
- About: version, build date, GitHub link

---

## Design decisions locked in

| Decision | Rationale |
|---|---|
| **SQLite over JSON files** | Real relational data (playlist ↔ song many-to-many with ordering); JSON would require reinventing joins |
| **Hand-rolled JSON parsing** | No third-party JSON dep to keep the tree lean; parser lives in one file per API |
| **No Maven/Gradle** | `compile.ps1` + `run.ps1` chosen; enforced |
| **Package stays `com.trackoff.*`** | (was `com.rankify` pre-rebrand — now fully renamed) |
| **Data folder `%USERPROFILE%\.trackoff\`** | (was `.rankify` / `.rkfy` pre-rebrand — now fully renamed) |
| **Auth Code flow, not PKCE** | Simpler for a desktop app with a client secret we control; PKCE would be needed only for pure-public clients |
| **OAuth callback loopback ports 47821/47822/47823** | All three registered in Spotify dev app; fallback if a port is taken |
| **Spotify features are opt-in** | Local-first principle — the app works without any auth |
| **Sync is pull-only in Phase 1** | Push introduces conflict resolution and destructive-action UX that deserves its own phase |
| **Last.fm is fully optional** | Users may not have accounts; the entire feature is hidden if not linked |

---

## Known constraints from the dev environment

- **Corporate network blocks:**
  - `accounts.spotify.com` (403 / block page) — OAuth login impossible at work
  - `api.spotify.com` (403 / block page) — API calls impossible at work
  - `ws.audioscrobbler.com` (403) — Last.fm impossible at work
- **Workaround:** all network-touching code is compile-verified at work, runtime-verified at home. Any code path that requires network is committed to git for testing on the home machine.
- **Corporate TLS inspection:** work machine may require `-Djavax.net.ssl.trustStoreType=Windows-ROOT` JVM flag for HTTPS to succeed. Documented for the home machine as unnecessary.

---

## What Phase 1 explicitly does NOT include

Deferred to Phase 2 or later, to keep Phase 1 shippable:

- ❌ Pushing local changes back to Spotify (create/modify/delete playlists on Spotify)
- ❌ Drag-and-drop reordering of songs within a playlist
- ❌ Multi-select / batch operations (move N songs, delete N songs)
- ❌ Playlist folders / grouping
- ❌ Song search across all of Spotify (only local search in Phase 1)
- ❌ Album view / artist view / genre browser
- ❌ Playing full tracks (30s Spotify previews are already supported in ranker/swipe; that stays)
- ❌ Sharing / exporting playlists in any format other than the existing CSV
- ❌ Cross-device sync of the local DB
- ❌ Any kind of account system for Trackoff itself

---

## Definition of "Phase 1 done"

All of the following must be true:

- [ ] User can launch the app fresh (empty DB) and see a welcome screen
- [ ] User can import a CSV and see their library populated in Library View
- [ ] User can browse playlists and songs offline, with zero network dependency
- [ ] User can optionally connect Spotify → sync playlists into local DB
- [ ] User can optionally connect Last.fm → see play counts in library
- [ ] All existing features (Ranker, Tier List, Swipe) still work and can operate on local-DB playlists as well as CSV / Spotify sources
- [ ] Settings view exposes connect/disconnect for both services, plus data-folder access and reset
- [ ] App degrades gracefully when offline — no crashes, no error spam, clear UI state
- [ ] Error messages for the three "blocked network" scenarios are human-readable and actionable
- [ ] No stray `rankify` / `rkfy` references anywhere in source, scripts, docs, or data folder

---

## File inventory target (end of Phase 1)

Rough estimate of the tree after all drops land:

```
src/com/trackoff/
├── Main.java
├── config/
│   ├── AppPaths.java
│   └── Settings.java
├── db/
│   ├── Database.java
│   ├── Dao.java
│   ├── Migrations.java
│   ├── PlaylistDao.java           ← new
│   ├── SongDao.java               ← new
│   ├── PlaylistSongDao.java       ← new
│   └── schema/V1__initial_schema.sql
├── io/
│   ├── csv/
│   │   ├── CsvLibraryImporter.java ← new
│   │   └── (existing CSV code)
│   ├── spotify/
│   │   ├── SpotifyAuth.java
│   │   ├── TokenStore.java
│   │   ├── OAuthCallbackServer.java
│   │   ├── SpotifyApi.java         ← new
│   │   └── SpotifyJsonParser.java  ← new
│   └── lastfm/
│       ├── LastFmClient.java       ← new
│       └── LastFmJsonParser.java   ← new
├── model/
│   ├── Song.java
│   ├── Playlist.java
│   └── (existing)
├── net/
│   └── NetworkStatus.java          ← new
├── ranker/
│   └── (existing)
├── service/
│   ├── LibraryService.java         ← new
│   ├── SyncService.java            ← new
│   └── ScrobbleService.java        ← new
└── ui/
    ├── MainView.java               ← updated (adds Library mode)
    ├── ComparisonView.java
    ├── ResultView.java
    ├── TierListView.java
    ├── SwipeView.java
    ├── Theme.java
    ├── styles.css
    ├── common/
    │   └── OfflineBanner.java      ← new
    ├── library/
    │   ├── LibraryView.java        ← new
    │   ├── PlaylistListPane.java   ← new
    │   ├── SongTablePane.java      ← new
    │   └── LibraryToolbar.java     ← new
    └── settings/
        ├── SettingsView.java       ← new
        ├── ConnectSpotifyView.java ← new
        └── ConnectLastFmView.java  ← new (partial exists)
```

**Total new files:** ~20
**Total modified files:** ~3 (`Main.java`, `MainView.java`, `styles.css`)

---

## Immediate next step (agreed)

**Drop 3: DAOs + CSV import + `LibraryService`.** This is the highest-leverage next move because:
1. It's fully testable at work (zero network)
2. It produces a populated database, which unblocks all subsequent UI work
3. It's a real user-facing feature (users can bootstrap Trackoff from a CSV without ever authing)
4. It exercises the schema shipped in Drop 1 for the first time
5. The interfaces it establishes (`LibraryService`) are the same ones the eventual Spotify sync will feed into

After Drop 3, Drop 4 (Library View shell) becomes almost trivial because the data layer beneath it is real.

---

*Last updated: end of the "corporate network blocks Spotify + Last.fm" diagnostic session. Trackoff rebrand fully applied (Rankify → Trackoff, .rkfy → .trackoff).*
