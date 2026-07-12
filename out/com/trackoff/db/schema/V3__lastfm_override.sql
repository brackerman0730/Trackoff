-- Trackoff schema V3 — manual Last.fm track reassignment
-- =====================================================================
--
-- Lets the user override which Last.fm track a song's play count is
-- looked up under (right-click a row in the Last.fm Manager and paste
-- a Last.fm track URL) — for songs the automatic artist/title matching
-- can't resolve correctly. Keyed on the song, so it survives restarts
-- and applies wherever this song appears.

ALTER TABLE songs ADD COLUMN lastfm_override_artist TEXT;
ALTER TABLE songs ADD COLUMN lastfm_override_title TEXT;
