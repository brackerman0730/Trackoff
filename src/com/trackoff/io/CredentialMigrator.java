package com.trackoff.io;

import com.trackoff.config.AppPaths;
import com.trackoff.config.Settings;

import java.nio.file.Files;
import java.util.List;

/**
 * One-time migration of legacy plaintext Spotify credentials into
 * the SQLite settings table.
 *
 * The old app stored Client ID + Secret at
 * {@code %USERPROFILE%\.trackoff\spotify.txt} (two lines). We keep
 * reading it on startup so existing users don't have to re-enter
 * their creds — but we only migrate when the DB has nothing yet.
 *
 * Safe to call on every launch: the guard clauses at the top make it
 * a no-op after the first successful migration.
 */
public final class CredentialMigrator {

    private CredentialMigrator() {}

    /** Run the migration if applicable. Any error is swallowed with a log. */
    public static void migrateIfNeeded() {
        try {
            // Already migrated? Nothing to do.
            if (Settings.get(Settings.SPOTIFY_CLIENT_ID).isPresent()) return;

            // No legacy file? Also nothing to do.
            if (!Files.exists(AppPaths.legacyCredsFile())) return;

            List<String> lines = Files.readAllLines(AppPaths.legacyCredsFile());
            if (lines.size() < 2) return;

            String id     = lines.get(0).trim();
            String secret = lines.get(1).trim();
            if (id.isEmpty() || secret.isEmpty()) return;

            Settings.set(Settings.SPOTIFY_CLIENT_ID,     id);
            Settings.set(Settings.SPOTIFY_CLIENT_SECRET, secret);

            System.out.println("Migrated legacy Spotify credentials into SQLite.");
        } catch (Exception e) {
            // Non-fatal — user will just have to re-enter creds if this fails.
            System.err.println("Legacy credential migration failed: " + e.getMessage());
        }
    }
}