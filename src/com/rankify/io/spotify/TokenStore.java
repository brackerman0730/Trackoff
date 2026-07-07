package com.rankify.io.spotify;

import com.rankify.db.Dao;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage-and-freshness gate for Spotify OAuth tokens.
 *
 * We keep one row per service in the {@code oauth_tokens} table.
 * Callers ask this class for the current access token; if it's about
 * to expire, {@link SpotifyAuth#refresh} handles the exchange and this
 * class writes the new values back.
 *
 * Tokens are stored in plain text in the local SQLite DB. This is on
 * par with how most desktop apps handle it — the DB file sits under
 * the user's home directory, so it's protected by OS file perms.
 */
public final class TokenStore {

    /** How close to expiry counts as "expired" and triggers a refresh. */
    static final int REFRESH_SLACK_SECONDS = 60;

    private static final String SERVICE = "spotify";

    private TokenStore() {}

    /** Simple record so callers don't touch JDBC. */
    public record Tokens(
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            String scope
    ) {
        public boolean isFresh() {
            return Instant.now().plusSeconds(REFRESH_SLACK_SECONDS).isBefore(expiresAt);
        }
    }

    /** Current tokens, or empty if the user hasn't logged in yet. */
    public static Optional<Tokens> load() {
        return Dao.queryOne("""
                SELECT access_token, refresh_token, expires_at, scope
                FROM oauth_tokens WHERE service = ?
                """,
                rs -> {
                    try {
                        return new Tokens(
                                rs.getString(1),
                                rs.getString(2),
                                Instant.parse(rs.getString(3)),
                                rs.getString(4));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                SERVICE);
    }

    /** Upsert. Called after initial login and after every refresh. */
    public static void save(Tokens t) {
        Dao.exec("""
                INSERT INTO oauth_tokens(service, access_token, refresh_token, expires_at, scope)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(service) DO UPDATE SET
                    access_token  = excluded.access_token,
                    refresh_token = excluded.refresh_token,
                    expires_at    = excluded.expires_at,
                    scope         = excluded.scope,
                    updated_at    = CURRENT_TIMESTAMP
                """,
                SERVICE,
                t.accessToken(),
                t.refreshToken(),
                t.expiresAt().toString(),
                t.scope());
    }

    /** Wipe stored tokens (used by "Log out of Spotify"). */
    public static void clear() {
        Dao.exec("DELETE FROM oauth_tokens WHERE service = ?", SERVICE);
    }

    /** True if we have any tokens on file (fresh or stale). */
    public static boolean isLinked() {
        return load().isPresent();
    }
}