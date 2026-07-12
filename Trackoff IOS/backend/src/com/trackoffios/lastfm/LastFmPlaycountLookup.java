package com.trackoffios.lastfm;

import com.trackoffios.SettingsStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synchronous play-count resolution, ported from the desktop app's
 * LastFmPlaycountLookup — same search-first strategy (not a direct
 * track.getinfo call), which live testing showed is necessary because:
 *   - Spotify's comma-joined multi-artist credit ("Fetty Wap, Remy
 *     Boyz") is itself a distinct (wrong) Last.fm artist entity.
 *   - track.getinfo's own auto-correction can silently redirect to the
 *     WRONG same-named track under a different artist credit.
 * No JavaFX/async plumbing needed here — this server handles concurrency
 * per HTTP request already, so this is just a plain blocking call with
 * an in-memory cache (a request-scoped equivalent of the desktop's
 * CACHE map).
 */
public final class LastFmPlaycountLookup {

    private static final ConcurrentHashMap<String, Long> CACHE = new ConcurrentHashMap<>();

    private LastFmPlaycountLookup() {}

    /**
     * @param songId used only to check for a manual override (right-click
     *               "reassign" equivalent — persisted per-song, not per
     *               artist/title, so it survives a title/artist correction).
     */
    public static long resolve(String songId, String artist, String title) {
        Optional<String[]> override = readOverride(songId);
        String effectiveArtist = override.map(o -> o[0]).orElse(artist);
        String effectiveTitle  = override.map(o -> o[1]).orElse(title);

        String key = (effectiveArtist + " " + effectiveTitle).toLowerCase();
        Long cached = CACHE.get(key);
        if (cached != null) return cached;

        long playcount;
        try {
            playcount = override.isPresent()
                    ? LastFmClient.fetchLinkedTrackPlaycount(effectiveArtist, effectiveTitle).orElse(0L)
                    : resolvePlaycount(effectiveArtist, effectiveTitle);
        } catch (Exception e) {
            return -1L;   // signal "failed" — caller should NOT cache/treat as a real 0
        }
        CACHE.put(key, playcount);
        return playcount;
    }

    public static void setOverride(String songId, String artist, String title) {
        SettingsStore.set("override." + songId + ".artist", artist);
        SettingsStore.set("override." + songId + ".title", title);
    }

    public static void clearOverride(String songId) {
        SettingsStore.remove("override." + songId + ".artist");
        SettingsStore.remove("override." + songId + ".title");
    }

    public static Optional<String[]> readOverride(String songId) {
        String a = SettingsStore.get("override." + songId + ".artist");
        String t = SettingsStore.get("override." + songId + ".title");
        return (a == null || t == null) ? Optional.empty() : Optional.of(new String[]{a, t});
    }

    private static long resolvePlaycount(String artist, String title) throws Exception {
        String primaryArtist = primaryArtist(artist);

        Optional<LastFmClient.TrackMatch> match = LastFmClient.searchLinkedTrack(title, primaryArtist);
        if (match.isPresent()) {
            Optional<Long> viaMatch = LastFmClient.fetchLinkedTrackPlaycount(match.get().artist(), match.get().name());
            if (viaMatch.isPresent()) return viaMatch.get();
        }
        return LastFmClient.fetchLinkedTrackPlaycount(primaryArtist, title).orElse(0L);
    }

    private static String primaryArtist(String artist) {
        int comma = artist.indexOf(',');
        return comma < 0 ? artist : artist.substring(0, comma).trim();
    }
}
