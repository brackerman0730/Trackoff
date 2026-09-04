package com.trackoff.io.lastfm;

import com.trackoff.config.Settings;
import com.trackoff.db.Dao;
import com.trackoff.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable side of the Last.fm play-count cache: the {@code songs}
 * table's {@code lastfm_playcount} columns, read back as a batch.
 *
 * Before this existed those columns were write-only — {@link
 * LastFmPlaycountLookup} filled them in as a "some future feature will
 * want this" convenience, and the Last.fm Manager still re-fetched
 * every song from the API on every single open (thousands of
 * rate-limited requests; minutes of watching bars trickle in). Now the
 * Manager renders from here instantly and only talks to Last.fm when
 * the user asks it to, via "Update Last.fm plays".
 *
 * Two details worth knowing:
 *   - A play count is scoped to the linked Last.fm ACCOUNT, not to the
 *     song, so every read is filtered by and every write stamped with
 *     the current username (schema V4). Switching accounts makes the
 *     old cache invisible rather than silently wrong.
 *   - SQLite caps a statement at 999 bound parameters and playlists
 *     here run well past that (the ~1400-song library the rate
 *     limiting was tuned against), so reads are chunked.
 */
public final class PlaycountStore {

    /** Bound parameters per statement, kept well under SQLITE_MAX_VARIABLE_NUMBER (999). */
    private static final int CHUNK = 400;

    private PlaycountStore() {}

    /** A cached count plus when it was fetched (SQLite CURRENT_TIMESTAMP format, UTC). */
    public record Cached(long playcount, String fetchedAt) {}

    /**
     * Cached counts for the given songs, keyed by song id. Songs with
     * no cached count for the current account are simply absent from
     * the map — callers render those as "not fetched yet" rather than
     * as a confirmed zero plays.
     */
    public static Map<String, Cached> load(Collection<String> songIds) {
        String user = currentUser();
        if (user == null || songIds.isEmpty()) return Map.of();

        Map<String, Cached> out = new HashMap<>();
        for (List<String> chunk : chunks(songIds)) {
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            Object[] params = new Object[chunk.size() + 1];
            params[0] = user;
            for (int i = 0; i < chunk.size(); i++) params[i + 1] = chunk.get(i);

            Dao.query("SELECT id, lastfm_playcount, COALESCE(lastfm_playcount_fetched_at, '') "
                            + "FROM songs "
                            + "WHERE lastfm_playcount IS NOT NULL "
                            + "  AND lastfm_playcount_user = ? "
                            + "  AND id IN (" + placeholders + ")",
                    rs -> {
                        try {
                            out.put(rs.getString(1), new Cached(rs.getLong(2), rs.getString(3)));
                            return Boolean.TRUE;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    params);
        }
        return out;
    }

    /**
     * Newest {@code fetched_at} across the given songs — "when were
     * these play counts last updated". Empty if none are cached.
     */
    public static Optional<String> newestFetchedAt(Map<String, Cached> cached) {
        return cached.values().stream()
                .map(Cached::fetchedAt)
                .filter(s -> !s.isBlank())
                .max(String::compareTo);   // "yyyy-MM-dd HH:mm:ss" sorts lexicographically
    }

    /** Write one song's count through to the DB. */
    public static void save(String songId, long playcount) {
        saveAll(Map.of(songId, playcount));
    }

    /**
     * Write a batch of counts in one transaction. Synchronized because
     * {@link Database} hands out a single shared Connection while the
     * updater's fetch pool is multi-threaded — funnelling writes
     * through here keeps JDBC use serialized.
     */
    public static synchronized void saveAll(Map<String, Long> counts) {
        if (counts.isEmpty()) return;
        String user = currentUser();
        if (user == null) return;

        Connection conn = Database.connection();
        boolean prevAuto = true;
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE songs SET lastfm_playcount = ?, "
                            + "lastfm_playcount_user = ?, "
                            + "lastfm_playcount_fetched_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ?")) {
                for (Map.Entry<String, Long> e : counts.entrySet()) {
                    ps.setLong(1, e.getValue());
                    ps.setString(2, user);
                    ps.setString(3, e.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            // A cache write failing is never worth killing the update over.
        } finally {
            try { conn.setAutoCommit(prevAuto); } catch (Exception ignored) {}
        }
    }

    /** Forget the cached count for one song (current account), e.g. after a manual reassignment. */
    public static synchronized void clear(String songId) {
        String user = currentUser();
        if (user == null) return;
        Dao.exec("UPDATE songs SET lastfm_playcount = NULL, lastfm_playcount_fetched_at = NULL "
                + "WHERE id = ? AND lastfm_playcount_user = ?", songId, user);
    }

    // ==================================================================
    //  Display helpers
    // ==================================================================

    /**
     * "just now" / "3 hours ago" / "12 Mar 2026" for a SQLite
     * CURRENT_TIMESTAMP string. Those are UTC, not local — parsing one
     * as local time reads as several hours in the future in most of the
     * world and would render "just now" indefinitely.
     */
    public static String humanize(String sqliteTimestamp) {
        try {
            LocalDateTime utc = LocalDateTime.parse(sqliteTimestamp.trim().replace(' ', 'T'));
            ZonedDateTime then = utc.atZone(ZoneOffset.UTC);
            long minutes = Duration.between(then, ZonedDateTime.now(ZoneOffset.UTC)).toMinutes();

            if (minutes < 2)  return "just now";
            if (minutes < 60) return minutes + " minutes ago";
            long hours = minutes / 60;
            if (hours < 24)   return hours == 1 ? "1 hour ago" : hours + " hours ago";
            long days = hours / 24;
            if (days == 1)    return "yesterday";
            if (days < 7)     return days + " days ago";
            return then.withZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("d MMM yyyy"));
        } catch (Exception e) {
            return sqliteTimestamp;
        }
    }

    // ==================================================================

    static String currentUser() {
        return Settings.get(Settings.LASTFM_USERNAME).orElse(null);
    }

    private static List<List<String>> chunks(Collection<String> ids) {
        List<String> all = new ArrayList<>(new LinkedHashSet<>(ids));   // de-dupe, keep order
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return out;
    }
}
