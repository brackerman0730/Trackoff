package com.trackoffios;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Local key/value settings, persisted as a plain .properties file — the
 * companion-app equivalent of the desktop app's SQLite-backed Settings
 * class, just simpler since this is a single small server process.
 *
 * On first-ever run (no settings file yet), best-effort bootstraps
 * Spotify/Last.fm credentials from the desktop app's existing local DB
 * (same machine, same user, already-consented-to values) so you don't
 * have to re-enter them. Never touches the desktop app's files — only
 * reads from them once, and only if this app has no settings yet.
 */
public final class SettingsStore {

    private static final Path FILE = Paths.get(System.getProperty("user.home"), ".trackoff-ios", "settings.properties");
    private static final Properties props = new Properties();

    static {
        load();
        if (props.isEmpty()) bootstrapFromDesktopApp();
    }

    private SettingsStore() {}

    public static synchronized void load() {
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.exists(FILE)) {
                try (InputStream in = Files.newInputStream(FILE)) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            System.err.println("[SettingsStore] load failed: " + e.getMessage());
        }
    }

    public static synchronized String get(String key) {
        return props.getProperty(key);
    }

    public static synchronized String get(String key, String fallback) {
        return props.getProperty(key, fallback);
    }

    public static synchronized void set(String key, String value) {
        if (value == null) props.remove(key);
        else props.setProperty(key, value);
        save();
    }

    public static synchronized void remove(String key) {
        props.remove(key);
        save();
    }

    private static void save() {
        try (OutputStream out = Files.newOutputStream(FILE)) {
            props.store(out, "Trackoff iOS settings — local to this machine, never uploaded anywhere");
        } catch (IOException e) {
            System.err.println("[SettingsStore] save failed: " + e.getMessage());
        }
    }

    /**
     * Best-effort: copy spotify.client_id/client_secret and
     * lastfm.username/api_key out of the desktop app's SQLite DB, if it
     * exists, so the phone version doesn't force you to re-enter
     * credentials you already set up. Silently does nothing on any
     * failure (missing DB, locked file, missing driver, etc.) — this is
     * a convenience, not a requirement.
     */
    private static void bootstrapFromDesktopApp() {
        Path desktopDb = Paths.get(System.getProperty("user.home"), ".trackoff", "trackoff.db");
        if (!Files.exists(desktopDb)) return;

        try {
            Class.forName("org.sqlite.JDBC");
            // "?mode=ro" needs a "file:" prefix to be parsed as a URI param
            // by SQLite's C layer — without it, it's read as part of the
            // literal filename (which then fails to open). Not requesting
            // read-only isn't a real risk here since this class only ever
            // SELECTs from the desktop DB.
            String url = "jdbc:sqlite:" + desktopDb.toAbsolutePath();
            try (Connection conn = DriverManager.getConnection(url);
                 Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT key, value FROM settings")) {
                boolean any = false;
                while (rs.next()) {
                    String key = rs.getString(1);
                    String value = rs.getString(2);
                    if (key == null || value == null) continue;
                    if (key.equals("spotify.client_id") || key.equals("spotify.client_secret")
                            || key.equals("lastfm.username") || key.equals("lastfm.api_key")) {
                        props.setProperty(key, value);
                        any = true;
                    }
                }
                if (any) {
                    save();
                    System.out.println("[SettingsStore] Bootstrapped Spotify/Last.fm credentials from the desktop app.");
                }
            }
        } catch (Exception e) {
            System.err.println("[SettingsStore] Desktop credential bootstrap skipped: " + e.getMessage());
        }
    }
}
