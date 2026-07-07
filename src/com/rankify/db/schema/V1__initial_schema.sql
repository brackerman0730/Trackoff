-- Trackoff's own settings (key/value store for simple stuff)
CREATE TABLE settings (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- OAuth tokens (one row per service)
CREATE TABLE oauth_tokens (
    service         TEXT PRIMARY KEY,        -- 'spotify'
    access_token    TEXT NOT NULL,
    refresh_token   TEXT NOT NULL,
    expires_at      TEXT NOT NULL,           -- ISO-8601
    scope           TEXT,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The user's own Spotify account snapshot
CREATE TABLE spotify_user (
    id              TEXT PRIMARY KEY,        -- Spotify user ID
    display_name    TEXT,
    image_url       TEXT,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Cache of the user's playlists (refreshed on demand)
CREATE TABLE playlists (
    id              TEXT PRIMARY KEY,        -- Spotify playlist ID
    name            TEXT NOT NULL,
    description     TEXT,
    owner_id        TEXT,
    owner_name      TEXT,
    image_url       TEXT,
    track_count     INTEGER NOT NULL DEFAULT 0,
    is_owned        INTEGER NOT NULL DEFAULT 0,   -- boolean (user owns it)
    is_collaborative INTEGER NOT NULL DEFAULT 0,
    snapshot_id     TEXT,                    -- Spotify's snapshot id for change detection
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Songs we've seen (deduplicated across all playlists)
CREATE TABLE songs (
    id              TEXT PRIMARY KEY,        -- Spotify track ID
    title           TEXT NOT NULL,
    artist          TEXT NOT NULL,
    album           TEXT,
    image_url       TEXT,
    preview_url     TEXT,
    duration_ms     INTEGER,
    popularity      INTEGER,
    release_date    TEXT,                    -- ISO date, may be YYYY or YYYY-MM
    mbid            TEXT,                    -- MusicBrainz ID (Phase 3, nullable)
    bpm             REAL,                    -- (Phase 3, nullable)
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Junction table with ordering (order_index preserves playlist order)
CREATE TABLE playlist_songs (
    playlist_id     TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    song_id         TEXT NOT NULL REFERENCES songs(id),
    order_index     INTEGER NOT NULL,
    added_at        TEXT,                    -- Spotify's added_at (ISO-8601)
    added_by        TEXT,                    -- user ID who added it
    PRIMARY KEY (playlist_id, song_id)
);
CREATE INDEX idx_playlist_songs_order ON playlist_songs(playlist_id, order_index);

-- Snapshots for "what got deleted" detection (Phase 2 populates this)
CREATE TABLE playlist_snapshots (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    playlist_id     TEXT NOT NULL REFERENCES playlists(id),
    snapshot_id     TEXT NOT NULL,
    song_ids_json   TEXT NOT NULL,           -- JSON array of song IDs in order
    captured_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_snapshots_playlist ON playlist_snapshots(playlist_id, captured_at);

-- Placeholder tables Phase 2/3 will fill in (declared now so migrations stay tidy)
CREATE TABLE lastfm_scrobbles (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    track_name      TEXT NOT NULL,
    artist_name     TEXT NOT NULL,
    album_name      TEXT,
    played_at       TEXT NOT NULL            -- ISO-8601 UTC
);
CREATE INDEX idx_scrobbles_time     ON lastfm_scrobbles(played_at);
CREATE INDEX idx_scrobbles_track    ON lastfm_scrobbles(artist_name, track_name);

CREATE TABLE song_blocks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    color           TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE block_songs (
    block_id        INTEGER NOT NULL REFERENCES song_blocks(id) ON DELETE CASCADE,
    song_id         TEXT NOT NULL REFERENCES songs(id),
    order_index     INTEGER NOT NULL,
    PRIMARY KEY (block_id, song_id)
);

-- Track schema version (Migrations.java reads/writes this)
CREATE TABLE schema_version (
    version         INTEGER PRIMARY KEY,
    applied_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);