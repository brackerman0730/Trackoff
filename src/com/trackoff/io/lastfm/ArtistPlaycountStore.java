package com.trackoff.io.lastfm;

import com.trackoff.db.Dao;
import com.trackoff.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Persisted cache of "how many times has this account played this
 * artist", the artist-level twin of {@link PlaycountStore}.
 *
 * Same contract as its song counterpart: reads and writes are scoped to
 * the currently-linked Last.fm account, absent means "never fetched"
 * (not "zero plays"), and reads are chunked to stay under SQLite's
 * 999-parameter statement limit.
 *
 * Artists are matched on a lowercased, trimmed key because they only
 * exist in this app as a free-text column on {@code songs} — there's no
 * artist entity to hang an id off. The as-credited spelling is kept
 * alongside for display.
 */
public final class ArtistPlaycountStore {

    private static final int CHUNK = 400;

    private ArtistPlaycountStore() {}

    /** A cached artist count plus when it was fetched. */
    public record Cached(String displayName, long playcount, String fetchedAt) {}

    /** One artist's freshly-fetched count, ready to persist. */
    public record ArtistPlays(String key, String displayName, long playcount) {}

    /** Normalized match key for an artist name. */
    public static String key(String artistName) {
        return artistName == null ? "" : artistName.trim().toLowerCase();
    }

    /** Cached counts for the given artist keys. Missing entries were never fetched. */
    public static Map<String, Cached> load(Collection<String> artistKeys) {
        String user = PlaycountStore.currentUser();
        if (user == null || artistKeys.isEmpty()) return Map.of();

        Map<String, Cached> out = new HashMap<>();
        for (List<String> chunk : chunks(artistKeys)) {
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            Object[] params = new Object[chunk.size() + 1];
            params[0] = user;
            for (int i = 0; i < chunk.size(); i++) params[i + 1] = chunk.get(i);

            Dao.query("SELECT artist_key, artist_name, playcount, COALESCE(fetched_at, '') "
                            + "FROM lastfm_artist_playcounts "
                            + "WHERE lastfm_user = ? AND artist_key IN (" + placeholders + ")",
                    rs -> {
                        try {
                            out.put(rs.getString(1),
                                    new Cached(rs.getString(2), rs.getLong(3), rs.getString(4)));
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
     * Write a batch in one transaction. Synchronized for the same reason
     * {@link PlaycountStore#saveAll} is: one shared JDBC Connection, a
     * multi-threaded fetch pool feeding it.
     */
    public static synchronized void saveAll(Collection<ArtistPlays> rows) {
        if (rows.isEmpty()) return;
        String user = PlaycountStore.currentUser();
        if (user == null) return;

        Connection conn = Database.connection();
        boolean prevAuto = true;
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO lastfm_artist_playcounts"
                            + "(artist_key, lastfm_user, artist_name, playcount, fetched_at) "
                            + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) "
                            + "ON CONFLICT(artist_key, lastfm_user) DO UPDATE SET "
                            + "  artist_name = excluded.artist_name, "
                            + "  playcount   = excluded.playcount, "
                            + "  fetched_at  = CURRENT_TIMESTAMP")) {
                for (ArtistPlays row : rows) {
                    ps.setString(1, row.key());
                    ps.setString(2, user);
                    ps.setString(3, row.displayName());
                    ps.setLong(4, row.playcount());
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

    private static List<List<String>> chunks(Collection<String> keys) {
        List<String> all = new ArrayList<>(new LinkedHashSet<>(keys));
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return out;
    }
}
