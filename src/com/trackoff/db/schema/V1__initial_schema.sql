-- Trackoff schema V1
-- =====================================================================
--
-- Every table gets an updated_at with CURRENT_TIMESTAMP default so we
-- can debug "when did this row change?" without any extra plumbing.
--
-- Boolean columns are INTEGER (0/1); SQLite has no real boolean type.
-- Timestamps are stored as ISO-8601 strings for readability.

-- =====================================================================
--  Meta / settings
-- =====================================================================

CREATE TABLE settings (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- =====================================================================
--  Authentication
-- =====================================================================

CREATE TABLE oauth_tokens (
    service        TEXT PRIMARY KEY,        -- 'spotify'
    access_token   TEXT NOT NULL,
    refresh_token  TEXT NOT NULL,
    expires_at     TEXT NOT NULL,           -- ISO-8601
    scope          TEXT,
    updated_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================================
--  Spotify data cache
-- =====================================================================

CREATE TABLE spotify_user (
    id             TEXT PRIMARY KEY,
    display_name   TEXT,
    image_url      TEXT,
    updated_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE playlists (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    description       TEXT,
    owner_id          TEXT,
    owner_name        TEXT,
    image_url         TEXT,
    track_count       INTEGER NOT NULL DEFAULT 0,
    is_owned          INTEGER NOT NULL DEFAULT 0,
    is_collaborative  INTEGER NOT NULL DEFAULT 0,
    snapshot_id       TEXT,
    updated_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE songs (
    id            TEXT PRIMARY KEY,   -- Spotify track ID, or "csv:<hash>"
    title         TEXT NOT NULL,
    artist        TEXT NOT NULL,
    album         TEXT,
    image_url     TEXT,
    preview_url   TEXT,
    duration_ms   INTEGER,
    popularity    INTEGER,
    release_date  TEXT,
    mbid          TEXT,               -- MusicBrainz ID (Phase 3)
    bpm           REAL,               -- Phase 3
    updated_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE playlist_songs (
    playlist_id  TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    song_id      TEXT NOT NULL REFERENCES songs(id),
    order_index  INTEGER NOT NULL,
    added_at     TEXT,
    added_by     TEXT,
    PRIMARY KEY (playlist_id, song_id)
);

CREATE INDEX idx_playlist_songs_order
    ON playlist_songs(playlist_id, order_index);

CREATE TABLE playlist_snapshots (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    playlist_id    TEXT NOT NULL REFERENCES playlists(id),
    snapshot_id    TEXT NOT NULL,
    song_ids_json  TEXT NOT NULL,
    captured_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_snapshots_playlist
    ON playlist_snapshots(playlist_id, captured_at);

-- =====================================================================
--  Last.fm data (Phase 2 populates)
-- =====================================================================

CREATE TABLE lastfm_scrobbles (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    track_name   TEXT NOT NULL,
    artist_name  TEXT NOT NULL,
    album_name   TEXT,
    played_at    TEXT NOT NULL       -- ISO-8601 UTC
);

CREATE INDEX idx_scrobbles_time
    ON lastfm_scrobbles(played_at);

CREATE INDEX idx_scrobbles_track
    ON lastfm_scrobbles(artist_name, track_name);

-- =====================================================================
--  Song blocks (Phase 3 fills this in via UI)
-- =====================================================================

CREATE TABLE song_blocks (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    color       TEXT,
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE block_songs (
    block_id     INTEGER NOT NULL REFERENCES song_blocks(id) ON DELETE CASCADE,
    song_id      TEXT NOT NULL REFERENCES songs(id),
    order_index  INTEGER NOT NULL,
    PRIMARY KEY (block_id, song_id)
);