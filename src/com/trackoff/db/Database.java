package com.trackoff.db;

import com.trackoff.config.AppPaths;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite connection singleton and startup orchestrator.
 *
 * Trackoff is single-user and single-process, so one shared connection
 * is more than enough — SQLite handles that gracefully. If we ever need
 * concurrency (background scrobble sync, etc.) we can switch to a pool
 * later without changing callers.
 *
 * Call {@link #init()} once at app startup — it opens the DB file,
 * turns on foreign keys, and runs any pending migrations.
 */
public final class Database {

    private static Connection conn;

    private Database() {}

    /** Open the database and apply migrations. Idempotent. */
    public static synchronized void init() throws Exception {
        if (conn != null) return;

        AppPaths.ensureDataDir();

        // Load the JDBC driver explicitly — some class-loader setups
        // don't auto-discover it, and being explicit costs us nothing.
        Class.forName("org.sqlite.JDBC");

        String url = "jdbc:sqlite:" + AppPaths.databaseFile().toAbsolutePath();
        conn = DriverManager.getConnection(url);

        // SQLite doesn't enforce foreign keys by default — enable them
        // per-connection so our ON DELETE CASCADE actually cascades.
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA journal_mode = WAL");   // faster + safer for our workload
        }

        Migrations.migrate(conn);
    }

    /** Shared connection. Never null after {@link #init()} succeeds. */
    public static Connection connection() {
        if (conn == null) {
            throw new IllegalStateException("Database.init() not called yet");
        }
        return conn;
    }

    /** Best-effort close on shutdown. */
    public static synchronized void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
            conn = null;
        }
    }
}