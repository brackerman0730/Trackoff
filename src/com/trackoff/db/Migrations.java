package com.trackoff.db;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Schema migrations, applied in ascending version order.
 *
 * How it works:
 *   1. Ensure {@code schema_version} table exists (V1 creates it, but
 *      we need it *before* running V1 — chicken/egg — so we create it
 *      unconditionally here first).
 *   2. Read {@code MAX(version)} from that table (0 if empty).
 *   3. For every {@link Migration} whose version is greater, execute it
 *      inside a transaction and stamp the version row.
 *
 * Adding a new migration = adding one entry to the {@link #ALL} list
 * and dropping a matching .sql file in {@code db/schema/}.
 */
final class Migrations {

    /** One versioned schema step. */
    private record Migration(int version, String resource) {}

    /** All migrations, in ascending version order. */
    private static final List<Migration> ALL = List.of(
            new Migration(1, "/com/trackoff/db/schema/V1__initial_schema.sql"),
            new Migration(2, "/com/trackoff/db/schema/V2__lastfm_manager.sql"),
            new Migration(3, "/com/trackoff/db/schema/V3__lastfm_override.sql")
    );

    private Migrations() {}

    /** Bring the database from its current version up to the latest. */
    static void migrate(Connection conn) throws Exception {
        ensureVersionTable(conn);
        int current = currentVersion(conn);

        for (Migration m : ALL) {
            if (m.version() <= current) continue;
            applyMigration(conn, m);
            recordVersion(conn, m.version());
        }
    }

    // ------------------------------------------------------------------

    private static void ensureVersionTable(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version    INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """);
        }
    }

    private static int currentVersion(Connection conn) throws Exception {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void applyMigration(Connection conn, Migration m) throws Exception {
        String sql = readResource(m.resource());
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            for (String stmt : splitStatements(sql)) {
                if (stmt.isBlank()) continue;
                s.execute(stmt);
            }
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw new RuntimeException(
                    "Migration V" + m.version() + " failed: " + e.getMessage(), e);
        } finally {
            conn.setAutoCommit(prevAuto);
        }
    }

    private static void recordVersion(Connection conn, int version) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_version(version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    /**
     * Read a classpath resource into a string.
     * We look it up via the class loader so it works both when
     * classes are loose in {@code out\} and when they're jarred.
     */
    private static String readResource(String path) throws Exception {
        try (InputStream in = Migrations.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Missing migration resource: " + path);
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        }
    }

    /**
     * Split a SQL script on {@code ;} outside of quotes/comments.
     * SQLite's JDBC driver runs one statement per {@code execute()},
     * so we can't just paste the whole file into a single call.
     * This is a small hand-rolled splitter that handles line comments
     * ({@code --}) and single-quoted strings — plenty for our schema.
     */
    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingleQuote = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);

            // Line comment: skip to end of line.
            if (!inSingleQuote && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                cur.append(c);
                i++;
                continue;
            }
            if (c == ';' && !inSingleQuote) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }
        if (!cur.toString().trim().isEmpty()) out.add(cur.toString().trim());
        return out;
    }
}