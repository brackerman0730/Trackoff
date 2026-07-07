package com.rankify.config;

import com.rankify.db.Dao;

import java.util.Optional;

/**
 * Key/value settings store, backed by the {@code settings} table.
 *
 * We use this for things that don't deserve their own table — Last.fm
 * username, feature toggles, "last sync time," etc. Keys are strings
 * by convention; values are always strings (parse on the way out).
 */
public final class Settings {

    // Well-known keys (kept here so IDEs autocomplete them).
    public static final String LASTFM_USERNAME    = "lastfm.username";
    public static final String LASTFM_API_KEY     = "lastfm.api_key";
    public static final String LASTFM_PLAYCOUNT   = "lastfm.playcount";
    public static final String SPOTIFY_CLIENT_ID     = "spotify.client_id";
    public static final String SPOTIFY_CLIENT_SECRET = "spotify.client_secret";

    private Settings() {}

    /** Fetch a value; empty if unset. */
    public static Optional<String> get(String key) {
        return Dao.queryOne(
                "SELECT value FROM settings WHERE key = ?",
                rs -> {
                    try { return rs.getString(1); }
                    catch (Exception e) { throw new RuntimeException(e); }
                },
                key);
    }

    /** Same as {@link #get(String)} but returns the fallback if unset. */
    public static String getOr(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    /** Insert or update. */
    public static void set(String key, String value) {
        Dao.exec("""
                INSERT INTO settings(key, value)
                VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET
                    value      = excluded.value,
                    updated_at = CURRENT_TIMESTAMP
                """, key, value);
    }

    /** Delete a setting. No-op if it wasn't set. */
    public static void unset(String key) {
        Dao.exec("DELETE FROM settings WHERE key = ?", key);
    }
}