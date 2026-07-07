package com.rankify.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central place for filesystem paths Trackoff cares about.
 *
 * Everything lives under {@code %USERPROFILE%\.trackoff\} on Windows —
 * a single tidy folder so uninstalling (or backing up) is one delete.
 *
 * Also handles the tiny chore of creating the directory the first time
 * we need it, so callers never have to think about {@code mkdirs}.
 */
public final class AppPaths {

    private AppPaths() {}

    /** The root Trackoff data directory: {@code %USERPROFILE%\.trackoff}. */
    public static Path dataDir() {
        return Paths.get(System.getProperty("user.home"), ".trackoff");
    }

    /** SQLite database file. */
    public static Path databaseFile() {
        return dataDir().resolve("trackoff.db");
    }

    /**
     * Legacy Spotify credentials file (Client ID + Secret, plaintext).
     * We still read this so existing users don't have to re-enter creds
     * on first launch after upgrading.
     */
    public static Path legacyCredsFile() {
        // Note: the folder is still called .rankify for backwards compat.
        // We migrate the contents into SQLite on first run.
        return Paths.get(System.getProperty("user.home"), ".rankify", "spotify.txt");
    }

    /** Ensure the data directory exists; safe to call repeatedly. */
    public static void ensureDataDir() throws IOException {
        Files.createDirectories(dataDir());
    }
}