package com.trackoff.ui;

import com.trackoff.config.Settings;
import com.trackoff.db.Dao;
import com.trackoff.io.ProgressStore;
import com.trackoff.io.SpotifyPlaylistSource;
import com.trackoff.io.lastfm.LastFmClient;
import com.trackoff.io.spotify.SpotifyApi;
import com.trackoff.model.Playlist;
import com.trackoff.ranking.AdaptiveMergeSortRanker;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * "My Library" — lists the signed-in user's Spotify playlists as a
 * grid of album-art tiles. Also shows a small stats strip at the top:
 * Spotify display name (with avatar) + Last.fm scrobble count.
 *
 * Playlists are cached in the SQLite {@code playlists} table on every
 * fetch so a second visit is instant. The Refresh button re-fetches
 * from Spotify.
 *
 * Clicking a tile fetches that playlist's full track list from Spotify
 * (same OAuth session, via SpotifyPlaylistSource) and asks the user
 * whether to Rank it, Tier List it, or Swipe through it — the same
 * three destinations the old "paste a URL" flow could reach, just
 * without needing to know the URL.
 */
public final class LibraryView {

    private static final double TILE_SIZE = 168;

    private final Stage stage;

    private ScrollPane        grid;
    private TilePane          tiles;
    private ProgressIndicator spinner;
    private Label             statusLabel;
    private Label             spotifyStrip;
    private Label             lastfmStrip;
    /** Set by fetchAndLaunch() before calling launch(); read by the
     *  RANK branch there so the session meta can record the source. */
    private String currentPlaylistIdForRank;

    public LibraryView(Stage stage) { this.stage = stage; }

    // ==================================================================
    //  Assembly
    // ==================================================================

    public void show() {
        Label title = new Label("Your Library");
        title.getStyleClass().add("label-header");

        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("button-ghost");
        refresh.setOnAction(e -> loadAsync(true));

        Button back = new Button("Back");
        back.getStyleClass().add("button-ghost");
        back.setOnAction(e -> new MainView(stage).show());

        Region hspacer = new Region();
        HBox.setHgrow(hspacer, Priority.ALWAYS);

        HBox header = new HBox(10, title, hspacer, refresh, back);
        header.setAlignment(Pos.CENTER_LEFT);

        // Account strip
        spotifyStrip = new Label("Spotify: …");
        lastfmStrip  = new Label("Last.fm: …");
        spotifyStrip.getStyleClass().add("label-muted");
        lastfmStrip .getStyleClass().add("label-muted");
        HBox strip = new HBox(28, spotifyStrip, lastfmStrip);
        strip.setPadding(new Insets(0, 0, 6, 0));

        // Grid
        tiles = new TilePane();
        tiles.setHgap(14);
        tiles.setVgap(14);
        tiles.setPrefColumns(4);
        tiles.setPadding(new Insets(6, 0, 6, 0));

        grid = new ScrollPane(tiles);
        grid.setFitToWidth(true);
        grid.setStyle("-fx-background-color:transparent; -fx-background:transparent;");
        VBox.setVgrow(grid, Priority.ALWAYS);

        // Loading indicator
        spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        statusLabel = new Label("Loading playlists…");
        statusLabel.getStyleClass().add("label-muted");
        HBox loading = new HBox(10, spinner, statusLabel);
        loading.setAlignment(Pos.CENTER_LEFT);
        loading.setPadding(new Insets(6, 0, 6, 0));

        VBox root = new VBox(14, header, strip, loading, grid);
        root.setPadding(new Insets(24));

        Scene scene = new Scene(root, 900, 720);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Trackoff — Library");

        loadAsync(false);
    }

    // ==================================================================
    //  Data loading (playlist list)
    // ==================================================================

    /**
     * @param force true = ignore the cache and re-fetch from Spotify
     */
    private void loadAsync(boolean force) {
        setLoading(true, "Loading playlists…");

        Task<List<PlaylistRow>> task = new Task<>() {
            @Override protected List<PlaylistRow> call() throws Exception {
                populateAccountStrips();

                // If we have cached rows and the caller didn't ask for
                // a hard refresh, show those instantly; still kick off
                // a background refresh to keep the cache warm.
                List<PlaylistRow> cached = readCache();
                if (!force && !cached.isEmpty()) {
                    // Fire-and-forget refresh in the background.
                    new Thread(() -> {
                        try { fetchAndCacheFromSpotify(); }
                        catch (Exception ignored) {}
                    }, "library-bg-refresh").start();
                    return cached;
                }

                fetchAndCacheFromSpotify();
                return readCache();
            }
        };

        task.setOnSucceeded(ev -> {
            List<PlaylistRow> rows = task.getValue();
            renderTiles(rows);
            setLoading(false,
                    rows.isEmpty()
                            ? "No playlists found."
                            : rows.size() + " playlists");
        });
        task.setOnFailed(ev -> {
            Throwable t = task.getException();
            setLoading(false, "Failed to load.");
            Alert a = new Alert(Alert.AlertType.ERROR,
                    "Couldn't load your library:\n"
                            + (t == null ? "unknown" : t.getMessage()));
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        });
        new Thread(task, "library-load").start();
    }

    private void populateAccountStrips() {
        // Spotify user
        try {
            String meJson = SpotifyApi.get("/me");
            String display = firstJsonString(meJson, "display_name");
            String userId  = firstJsonString(meJson, "id");
            String avatar  = firstImageUrl(meJson);

            Dao.exec("""
                    INSERT INTO spotify_user(id, display_name, image_url)
                    VALUES (?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        display_name = excluded.display_name,
                        image_url    = excluded.image_url,
                        updated_at   = CURRENT_TIMESTAMP
                    """, userId, display, avatar);

            String label = "Spotify: " + (display != null ? display : userId);
            Platform.runLater(() -> spotifyStrip.setText(label));
        } catch (Exception ex) {
            Platform.runLater(() -> spotifyStrip.setText("Spotify: (error) " + ex.getMessage()));
        }

        // Last.fm
        try {
            if (LastFmClient.isLinked()) {
                long n = Long.parseLong(Settings.getOr(Settings.LASTFM_PLAYCOUNT, "0"));
                String user = Settings.getOr(Settings.LASTFM_USERNAME, "?");
                String label = "Last.fm: " + user + "  ·  "
                        + String.format("%,d", n) + " scrobbles";
                Platform.runLater(() -> lastfmStrip.setText(label));
            } else {
                Platform.runLater(() -> lastfmStrip.setText("Last.fm: not connected"));
            }
        } catch (Exception e) {
            Platform.runLater(() -> lastfmStrip.setText("Last.fm: (error)"));
        }
    }

    /**
     * Pull every playlist from /me/playlists, page by page, and upsert
     * them into the DB. Playlists we no longer follow are removed.
     */
    private void fetchAndCacheFromSpotify() throws Exception {
        List<String> seenIds = new ArrayList<>();
        String next = "/me/playlists?limit=50";
        String myUserId = firstJsonString(SpotifyApi.get("/me"), "id");

        while (next != null) {
            String body = SpotifyApi.get(next);

            // Iterate the "items" array by scanning object boundaries.
            int itemsIdx = body.indexOf("\"items\"");
            if (itemsIdx < 0) break;
            int arrStart = body.indexOf('[', itemsIdx);
            int arrEnd   = matchingBracket(body, arrStart);
            if (arrStart < 0 || arrEnd < 0) break;

            String arr = body.substring(arrStart + 1, arrEnd);
            for (String obj : splitTopLevelObjects(arr)) {
                String id       = firstJsonString(obj, "id");
                String name     = firstJsonString(obj, "name");
                String desc     = firstJsonString(obj, "description");
                String snapshot = firstJsonString(obj, "snapshot_id");
                String image    = firstImageUrl(obj);
                int    tracks   = firstJsonInt(obj, "total");
                boolean collab  = "true".equals(firstJsonBool(obj, "collaborative"));

                // Owner info
                int ownerIdx = obj.indexOf("\"owner\"");
                String ownerId   = null, ownerName = null;
                if (ownerIdx >= 0) {
                    ownerId   = firstJsonStringAfter(obj, ownerIdx, "id");
                    ownerName = firstJsonStringAfter(obj, ownerIdx, "display_name");
                }
                boolean owned = ownerId != null && ownerId.equals(myUserId);

                if (id == null || name == null) continue;
                seenIds.add(id);

                Dao.exec("""
                    INSERT INTO playlists(id, name, description, owner_id, owner_name,
                        image_url, track_count, is_owned, is_collaborative, snapshot_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        name             = excluded.name,
                        description      = excluded.description,
                        owner_id         = excluded.owner_id,
                        owner_name       = excluded.owner_name,
                        image_url        = excluded.image_url,
                        track_count      = excluded.track_count,
                        is_owned         = excluded.is_owned,
                        is_collaborative = excluded.is_collaborative,
                        snapshot_id      = excluded.snapshot_id,
                        updated_at       = CURRENT_TIMESTAMP
                    """,
                    id, name, desc, ownerId, ownerName, image,
                    tracks, owned ? 1 : 0, collab ? 1 : 0, snapshot);
            }

            next = firstJsonStringOrNull(body, "next");
        }

        // Drop cached rows that no longer exist in the user's library.
        if (!seenIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(seenIds.size(), "?"));
            Object[] params = seenIds.toArray();
            Dao.exec("DELETE FROM playlists WHERE id NOT IN (" + placeholders + ")", params);
        }
    }

    /** Load the {@code playlists} table into UI-ready rows. */
    private List<PlaylistRow> readCache() {
        return Dao.query("""
                SELECT id, name, owner_name, image_url, track_count, is_owned
                FROM playlists
                ORDER BY is_owned DESC, LOWER(name)
                """,
                rs -> {
                    try {
                        return new PlaylistRow(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getInt(5),
                                rs.getInt(6) != 0);
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
    }

    // ==================================================================
    //  Rendering
    // ==================================================================

    private record PlaylistRow(String id, String name, String owner,
                               String imageUrl, int trackCount, boolean owned) {}

    private void renderTiles(List<PlaylistRow> rows) {
        tiles.getChildren().clear();
        for (PlaylistRow r : rows) tiles.getChildren().add(buildTile(r));
    }

    private StackPane buildTile(PlaylistRow r) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("library-tile");
        tile.setPrefSize(TILE_SIZE, TILE_SIZE + 56);

        VBox body = new VBox(8);
        body.setAlignment(Pos.TOP_LEFT);

        // Art
        StackPane artHolder = new StackPane();
        artHolder.setPrefSize(TILE_SIZE, TILE_SIZE);
        if (r.imageUrl() != null && !r.imageUrl().isEmpty()) {
            ImageView iv = new ImageView(new Image(r.imageUrl(),
                    TILE_SIZE, TILE_SIZE, false, true, true));
            iv.setFitWidth(TILE_SIZE);
            iv.setFitHeight(TILE_SIZE);
            iv.setPreserveRatio(false);
            Rectangle clip = new Rectangle(TILE_SIZE, TILE_SIZE);
            clip.setArcWidth(10);
            clip.setArcHeight(10);
            iv.setClip(clip);
            artHolder.getChildren().add(iv);
        } else {
            StackPane placeholder = new StackPane(new Label("♪"));
            placeholder.getStyleClass().add("song-tile-placeholder");
            placeholder.setPrefSize(TILE_SIZE, TILE_SIZE);
            artHolder.getChildren().add(placeholder);
        }

        Label name = new Label(r.name());
        name.getStyleClass().add("library-tile-title");
        name.setWrapText(true);
        name.setMaxWidth(TILE_SIZE);

        String sub = r.trackCount() + " tracks"
                + (r.owned() ? "  ·  yours" : (r.owner() != null ? "  ·  " + r.owner() : ""));
        Label subL = new Label(sub);
        subL.getStyleClass().add("library-tile-sub");

        body.getChildren().addAll(artHolder, name, subL);
        tile.getChildren().add(body);

        tile.setOnMouseClicked(ev -> onPlaylistClicked(r));

        return tile;
    }

    // ==================================================================
    //  Tile click → choose destination → fetch → launch
    // ==================================================================

    /** Where a picked playlist can go once we've fetched its tracks. */
    private enum LaunchMode { RANK, TIER, SWIPE }

    private void onPlaylistClicked(PlaylistRow r) {
        Alert choose = new Alert(Alert.AlertType.CONFIRMATION);
        choose.setTitle(r.name());
        choose.setHeaderText(r.name());
        choose.setContentText("What do you want to do with this playlist?");

        ButtonType rankBtn   = new ButtonType("Rank");
        ButtonType tierBtn   = new ButtonType("Tier List");
        ButtonType swipeBtn  = new ButtonType("Swipe");
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        choose.getButtonTypes().setAll(rankBtn, tierBtn, swipeBtn, cancelBtn);
        Theme.apply(choose.getDialogPane().getScene());

        var pick = choose.showAndWait().orElse(cancelBtn);
        if (pick == cancelBtn) return;

        LaunchMode mode = pick == rankBtn ? LaunchMode.RANK
                        : pick == tierBtn ? LaunchMode.TIER
                                          : LaunchMode.SWIPE;
        fetchAndLaunch(r, mode);
    }

    /**
     * Pulls the full track list for one playlist (id, not the whole
     * library) via the same OAuth client the tile grid uses, then
     * hands off to whichever screen the user picked. Runs off the FX
     * thread since this is a real network call, not a cache read.
     */
    private void fetchAndLaunch(PlaylistRow r, LaunchMode mode) {
        setLoading(true, "Loading \"" + r.name() + "\"…");
        currentPlaylistIdForRank = r.id();

        Task<Playlist> task = new Task<>() {
            @Override protected Playlist call() throws Exception {
                return new SpotifyPlaylistSource().load(r.id());
            }
        };

        task.setOnSucceeded(ev -> {
            setLoading(false, r.trackCount() + " tracks loaded");
            launch(task.getValue(), mode);
        });
        task.setOnFailed(ev -> {
            Throwable t = task.getException();
            setLoading(false, "Failed to load.");
            Alert a = new Alert(Alert.AlertType.ERROR,
                    "Couldn't load \"" + r.name() + "\":\n"
                            + (t == null ? "unknown" : t.getMessage()));
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        });
        new Thread(task, "library-playlist-fetch").start();
    }

    private void launch(Playlist playlist, LaunchMode mode) {
        switch (mode) {
            case RANK -> {
                if (playlist.size() < 2) { info("Playlist needs at least two songs to rank."); return; }
                AdaptiveMergeSortRanker ranker = new AdaptiveMergeSortRanker(playlist);
                // Record where this playlist came from so a save-and-resume later
                // can re-fetch it automatically without prompting for a CSV. The
                // playlist ID is what the resume flow feeds back into
                // SpotifyPlaylistSource.load().
                var meta = ProgressStore.SessionMeta.forSpotify(
                        currentPlaylistIdForRank, playlist);
                new ComparisonView(stage, playlist, ranker)
                        .withSessionMeta(meta)
                        .show();
            }
            case TIER -> {
                if (playlist.size() < 1) { info("Empty playlist."); return; }
                new TierListView(stage, playlist, playlist.songs()).show();
            }
            case SWIPE -> {
                if (playlist.size() < 1) { info("Empty playlist."); return; }
                new SwipeView(stage, playlist, playlist.songs()).show();
            }
        }
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg);
        Theme.apply(a.getDialogPane().getScene());
        a.showAndWait();
    }

    private void setLoading(boolean loading, String text) {
        spinner.setVisible(loading);
        statusLabel.setText(text);
    }

    // ==================================================================
    //  Tiny JSON helpers (same style as the Spotify/Last.fm clients)
    // ==================================================================

    private static String firstJsonString(String j, String key) {
        String v = firstJsonStringOrNull(j, key);
        return v == null ? "" : v;
    }

    private static String firstJsonStringOrNull(String j, String key) {
        return jsonStringAt(j, j.indexOf("\"" + key + "\""));
    }

    private static String firstJsonStringAfter(String j, int from, String key) {
        int at = j.indexOf("\"" + key + "\"", from);
        return jsonStringAt(j, at);
    }

    private static String jsonStringAt(String j, int keyIdx) {
        if (keyIdx < 0) return null;
        int colon = j.indexOf(':', keyIdx);
        if (colon < 0) return null;
        // Skip whitespace; string values start with a quote; null becomes null.
        int p = colon + 1;
        while (p < j.length() && Character.isWhitespace(j.charAt(p))) p++;
        if (p >= j.length()) return null;
        if (j.charAt(p) != '"') return null;   // could be null / number / object
        StringBuilder sb = new StringBuilder();
        p++;
        while (p < j.length()) {
            char c = j.charAt(p);
            if (c == '\\' && p + 1 < j.length()) { sb.append(j.charAt(p + 1)); p += 2; continue; }
            if (c == '"') break;
            sb.append(c);
            p++;
        }
        return sb.toString();
    }

    private static int firstJsonInt(String j, String key) {
        int i = j.indexOf("\"" + key + "\"");
        if (i < 0) return 0;
        int colon = j.indexOf(':', i);
        int p = colon + 1;
        while (p < j.length() && Character.isWhitespace(j.charAt(p))) p++;
        int start = p;
        while (p < j.length() && (Character.isDigit(j.charAt(p)) || j.charAt(p) == '-')) p++;
        if (p == start) return 0;
        try { return Integer.parseInt(j.substring(start, p)); }
        catch (Exception e) { return 0; }
    }

    private static String firstJsonBool(String j, String key) {
        int i = j.indexOf("\"" + key + "\"");
        if (i < 0) return "false";
        int colon = j.indexOf(':', i);
        int p = colon + 1;
        while (p < j.length() && Character.isWhitespace(j.charAt(p))) p++;
        if (j.regionMatches(p, "true",  0, 4)) return "true";
        if (j.regionMatches(p, "false", 0, 5)) return "false";
        return "false";
    }

    /**
     * Pull the largest image URL out of a Spotify object's "images"
     * array. Spotify orders them largest-first, so the first entry
     * is a good default.
     */
    private static String firstImageUrl(String j) {
        int arr = j.indexOf("\"images\"");
        if (arr < 0) return null;
        int bracket = j.indexOf('[', arr);
        if (bracket < 0) return null;
        return firstJsonStringAfter(j, bracket, "url");
    }

    /** Index of the ']' that closes the '[' at {@code openIdx}. */
    private static int matchingBracket(String s, int openIdx) {
        if (openIdx < 0) return -1;
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /**
     * Split a JSON array's contents into its top-level {@code {...}}
     * objects. Handles nested braces and quoted strings.
     */
    private static List<String> splitTopLevelObjects(String arr) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) out.add(arr.substring(start, i + 1)); }
        }
        return out;
    }
}