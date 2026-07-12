-- Trackoff schema V2 — Last.fm Manager
-- =====================================================================
--
-- Adds the two things Phase 1's schema was missing for the Last.fm
-- Manager feature: a flag marking a playlist as under Last.fm
-- management, and a per-song play-count cache (populated by
-- LastFmPlaycountLookup so future features, like a daily recheck, don't
-- need to re-fetch from Last.fm every time).

ALTER TABLE playlists ADD COLUMN lastfm_managed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE playlists ADD COLUMN lastfm_managed_at TEXT;

ALTER TABLE songs ADD COLUMN lastfm_playcount INTEGER;
ALTER TABLE songs ADD COLUMN lastfm_playcount_fetched_at TEXT;
