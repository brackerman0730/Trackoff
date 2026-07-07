package com.rankify.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A pinch of syntactic sugar over raw JDBC. Nothing magical — just
 * enough helpers to remove try-with-resources noise from callers.
 *
 * Every method uses {@link Database#connection()} under the hood.
 */
public final class Dao {

    private Dao() {}

    /** Run an update / insert / delete with positional parameters. */
    public static int exec(String sql, Object... params) {
        try (PreparedStatement ps = Database.connection().prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQL failed: " + sql, e);
        }
    }

    /** Return the first row mapped through {@code mapper}, or empty. */
    public static <T> Optional<T> queryOne(String sql,
                                           Function<ResultSet, T> mapper,
                                           Object... params) {
        try (PreparedStatement ps = Database.connection().prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapper.apply(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("SQL failed: " + sql, e);
        }
    }

    /** Return all rows mapped through {@code mapper}. */
    public static <T> List<T> query(String sql,
                                    Function<ResultSet, T> mapper,
                                    Object... params) {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = Database.connection().prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapper.apply(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("SQL failed: " + sql, e);
        }
        return out;
    }

    // ------------------------------------------------------------------

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}