package com.trackoff.db;

import com.trackoff.model.Playlist;
import com.trackoff.model.Song;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Persists a fetched {@link Playlist}'s song membership and order into
 * the {@code songs}/{@code playlist_songs} tables, and marks the
 * playlist as Last.fm-managed. One cohesive job, same spirit as
 * {@code com.trackoff.io.ProgressStore} — not a full DAO layer.
 *
 * Also drops a baseline row into {@code playlist_snapshots} so a future
 * daily-recheck feature has something to diff against; this class does
 * not implement that diffing itself.
 */
public final class PlaylistPersistence {

    private PlaylistPersistence() {}

    public static void persist(String playlistId, Playlist playlist) {
        upsertSongs(playlist);
        syncPlaylistSongs(playlistId, playlist);
        insertSnapshot(playlistId, playlist);
        markManaged(playlistId);
    }

    private static void upsertSongs(Playlist playlist) {
        for (Song s : playlist.songs()) {
            Dao.exec("""
                    INSERT INTO songs(id, title, artist, album, image_url, preview_url,
                        duration_ms, popularity, release_date, bpm)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        title        = excluded.title,
                        artist       = excluded.artist,
                        album        = excluded.album,
                        image_url    = excluded.image_url,
                        preview_url  = excluded.preview_url,
                        duration_ms  = excluded.duration_ms,
                        popularity   = excluded.popularity,
                        release_date = excluded.release_date,
                        bpm          = excluded.bpm,
                        updated_at   = CURRENT_TIMESTAMP
                    """,
                    s.id(), s.title(), s.artist(), s.album(), s.imageUrl(), s.previewUrl(),
                    s.durationSeconds() * 1000, s.popularity(), s.albumDate(), s.bpm());
        }
    }

    private static void syncPlaylistSongs(String playlistId, Playlist playlist) {
        List<Song> songs = playlist.songs();
        for (int i = 0; i < songs.size(); i++) {
            Dao.exec("""
                    INSERT INTO playlist_songs(playlist_id, song_id, order_index, added_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT(playlist_id, song_id) DO UPDATE SET
                        order_index = excluded.order_index
                    """, playlistId, songs.get(i).id(), i);
        }

        // Drop rows for songs no longer in the playlist (removed since last add/refresh).
        if (songs.isEmpty()) {
            Dao.exec("DELETE FROM playlist_songs WHERE playlist_id = ?", playlistId);
        } else {
            List<Object> params = new ArrayList<>();
            params.add(playlistId);
            for (Song s : songs) params.add(s.id());
            String placeholders = String.join(",", Collections.nCopies(songs.size(), "?"));
            Dao.exec("DELETE FROM playlist_songs WHERE playlist_id = ? AND song_id NOT IN (" + placeholders + ")",
                    params.toArray());
        }
    }

    private static void insertSnapshot(String playlistId, Playlist playlist) {
        // COALESCE to '' rather than selecting the raw (possibly NULL)
        // column — Dao.queryOne wraps the mapped value in Optional.of(),
        // which throws NPE on a null mapped value.
        String snapshotId = Dao.queryOne(
                        "SELECT COALESCE(snapshot_id, '') FROM playlists WHERE id = ?",
                        rs -> { try { return rs.getString(1); } catch (Exception e) { throw new RuntimeException(e); } },
                        playlistId)
                .orElse("");
        if (snapshotId.isEmpty()) {
            snapshotId = "local-" + Instant.now();
        }

        String songIdsJson = "[" + playlist.songs().stream()
                .map(s -> "\"" + escapeJson(s.id()) + "\"")
                .collect(Collectors.joining(",")) + "]";

        Dao.exec("""
                INSERT INTO playlist_snapshots(playlist_id, snapshot_id, song_ids_json, captured_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, playlistId, snapshotId, songIdsJson);
    }

    private static void markManaged(String playlistId) {
        Dao.exec("""
                UPDATE playlists
                SET lastfm_managed = 1, lastfm_managed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, playlistId);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
