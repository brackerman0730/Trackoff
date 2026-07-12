package com.trackoff.io.lastfm;

import com.trackoff.db.Dao;
import com.trackoff.model.Song;

import javafx.application.Platform;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.LongConsumer;

/**
 * Async/caching front door for per-song Last.fm play counts, same shape
 * as {@code com.trackoff.io.PreviewLookup} (built for the Deezer preview
 * work). Cache key is artist+title, not song id — play count is scoped
 * to the linked Last.fm account, not the song's identity.
 *
 * On every successful fetch this also writes through to
 * {@code songs.lastfm_playcount}/{@code lastfm_playcount_fetched_at}, so
 * a future daily-recheck feature has a cached value without re-fetching.
 */
public final class LastFmPlaycountLookup {

    private static final ConcurrentHashMap<String, Long> CACHE = new ConcurrentHashMap<>();

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, daemonThreadFactory());

    private LastFmPlaycountLookup() {}

    /** Sentinel delivered to {@code onResolved} when resolution genuinely failed (see class doc). */
    public static final long FAILED = -1L;

    /**
     * Resolve (from cache or via Last.fm) and deliver the result on the
     * FX thread. {@link LastFmClient}'s own rate limiting + retries
     * handle the common case (Last.fm throttling a large playlist's
     * worth of requests) transparently. If a lookup still fails after
     * those retries are exhausted, {@link #FAILED} is delivered instead
     * of a play count — NOT cached and NOT written to the DB. Silently
     * defaulting a failure to 0 previously meant a rate-limited request
     * partway through a large playlist got permanently (and wrongly)
     * recorded as "0 plays" for every song after that point.
     */
    public static void resolveAsync(Song song, LongConsumer onResolved) {
        String key = cacheKey(song);
        Long cached = CACHE.get(key);
        if (cached != null) {
            onResolved.accept(cached);
            return;
        }

        EXECUTOR.submit(() -> {
            long playcount;
            try {
                Optional<String[]> override = readOverride(song.id());
                playcount = override.isPresent()
                        ? LastFmClient.fetchLinkedTrackPlaycount(override.get()[0], override.get()[1]).orElse(0L)
                        : resolvePlaycount(song);
            } catch (Exception e) {
                Platform.runLater(() -> onResolved.accept(FAILED));
                return;
            }
            CACHE.put(key, playcount);
            writeThrough(song.id(), playcount);
            long result = playcount;
            Platform.runLater(() -> onResolved.accept(result));
        });
    }

    /**
     * Resolve via {@code track.search} first, NOT a direct
     * {@code track.getinfo} call. Two real mismatches confirmed this is
     * necessary, not just a nice-to-have:
     *   - Spotify's full comma-joined artist list ("Fetty Wap, Remy
     *     Boyz") is itself a distinct Last.fm artist entity, separate
     *     from "Fetty Wap" — a direct lookup against it "succeeds" but
     *     returns a different track with a real (globally) but
     *     always-zero (for this user) playcount.
     *   - Even using just the primary artist, track.getinfo's own
     *     auto-correction can silently redirect a slightly-off query to
     *     the WRONG same-named track under a different artist credit
     *     (e.g. "Niggas in Paris" by "JAY-Z & Kanye West" instead of the
     *     real "Ni**as in Paris" by "JAY-Z" with 1.5M listeners) —
     *     confidently wrong, not a detectable failure.
     * track.search's ranked-by-popularity results don't have either
     * failure mode, so it's used to resolve the canonical (artist,
     * title) pair before ever asking for a play count.
     */
    private static long resolvePlaycount(Song song) throws Exception {
        String primaryArtist = primaryArtist(song.artist());

        Optional<LastFmClient.TrackMatch> match = LastFmClient.searchLinkedTrack(song.title(), primaryArtist);
        if (match.isPresent()) {
            Optional<Long> viaMatch = LastFmClient.fetchLinkedTrackPlaycount(match.get().artist(), match.get().name());
            if (viaMatch.isPresent()) return viaMatch.get();
        }

        // Search came up empty (rare) — fall back to a direct lookup.
        return LastFmClient.fetchLinkedTrackPlaycount(primaryArtist, song.title()).orElse(0L);
    }

    /** Spotify joins multiple credited artists as "A, B, C" — the first is the primary credit. */
    private static String primaryArtist(String artist) {
        int comma = artist.indexOf(',');
        return comma < 0 ? artist : artist.substring(0, comma).trim();
    }

    // ==================================================================
    //  Manual reassignment — right-click a row in the Last.fm Manager
    //  and paste a Last.fm track URL to override the auto-matched
    //  (artist, title) pair this song's play count is looked up under.
    //  Persisted on the songs table (keyed by song id), so it survives
    //  restarts and applies wherever this song appears.
    // ==================================================================

    /** {artist, title} if this song has a manual override, else empty. */
    public static Optional<String[]> readOverride(String songId) {
        return Dao.queryOne(
                """
                SELECT lastfm_override_artist, lastfm_override_title
                FROM songs
                WHERE id = ? AND lastfm_override_artist IS NOT NULL AND lastfm_override_title IS NOT NULL
                """,
                rs -> {
                    try { return new String[]{rs.getString(1), rs.getString(2)}; }
                    catch (Exception e) { throw new RuntimeException(e); }
                },
                songId);
    }

    /** Song ids (from the given collection) that currently have a manual override set. */
    public static Set<String> songIdsWithOverride(Collection<String> songIds) {
        if (songIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", Collections.nCopies(songIds.size(), "?"));
        return new HashSet<>(Dao.query(
                "SELECT id FROM songs WHERE id IN (" + placeholders + ") "
                        + "AND lastfm_override_artist IS NOT NULL AND lastfm_override_title IS NOT NULL",
                rs -> { try { return rs.getString(1); } catch (Exception e) { throw new RuntimeException(e); } },
                songIds.toArray()));
    }

    public static void setOverride(String songId, String artist, String title) {
        Dao.exec("UPDATE songs SET lastfm_override_artist = ?, lastfm_override_title = ? WHERE id = ?",
                artist, title, songId);
    }

    public static void clearOverride(String songId) {
        Dao.exec("UPDATE songs SET lastfm_override_artist = NULL, lastfm_override_title = NULL WHERE id = ?", songId);
    }

    /** Drop this song's cached play count so the next resolveAsync re-fetches (e.g. after set/clearOverride). */
    public static void invalidate(Song song) {
        CACHE.remove(cacheKey(song));
    }

    private static void writeThrough(String songId, long playcount) {
        try {
            Dao.exec("""
                    UPDATE songs
                    SET lastfm_playcount = ?, lastfm_playcount_fetched_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, playcount, songId);
        } catch (Exception ignored) {
            // Cache write-through is a convenience for later features, not required now.
        }
    }

    private static String cacheKey(Song song) {
        return (song.artist() + " " + song.title()).toLowerCase();
    }

    private static ThreadFactory daemonThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "lastfm-playcount-lookup");
            t.setDaemon(true);
            return t;
        };
    }
}
