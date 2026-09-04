-- Trackoff schema V4 — make the cached play counts trustworthy across accounts
-- =====================================================================
--
-- V2 added songs.lastfm_playcount as a write-through cache that nothing
-- ever read back — the Last.fm Manager re-fetched every song from the
-- API on every open. It's now the primary source the Manager renders
-- from (refreshed on demand via "Update Last.fm plays"), which makes
-- one previously-harmless gap matter: a play count is scoped to the
-- linked Last.fm ACCOUNT, not to the song. Cached counts from a
-- previously-linked account would silently render as the current
-- account's listening history.
--
-- So: stamp every cached count with the account it came from, and only
-- read back rows matching the currently-linked user. Existing rows are
-- backfilled with the currently-linked username (the only account they
-- could have come from).

ALTER TABLE songs ADD COLUMN lastfm_playcount_user TEXT;

UPDATE songs
SET lastfm_playcount_user = (SELECT value FROM settings WHERE key = 'lastfm.username')
WHERE lastfm_playcount IS NOT NULL;
