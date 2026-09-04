-- Trackoff schema V5 — per-artist play counts
-- =====================================================================
--
-- Backs the Artists view's "how much do I listen to this artist at all"
-- number (artist.getinfo -> stats.userplaycount). Deliberately NOT the
-- same thing as summing a playlist's tracks: an artist can have 4000
-- scrobbles while the three of their songs in this playlist account for
-- 30 of them, and seeing that gap is the point of the view.
--
-- Its own table rather than a column on some artists table, because
-- Trackoff has no artist entity — artists exist only as a string on
-- songs. Keyed by (lowercased name, account) since a play count belongs
-- to a Last.fm account, same reasoning as songs.lastfm_playcount_user
-- in V4.

CREATE TABLE lastfm_artist_playcounts (
    artist_key   TEXT NOT NULL,          -- lowercased, trimmed; the match key
    lastfm_user  TEXT NOT NULL,
    artist_name  TEXT NOT NULL,          -- as credited, for display
    playcount    INTEGER NOT NULL,
    fetched_at   TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (artist_key, lastfm_user)
);
