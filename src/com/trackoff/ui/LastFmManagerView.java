package com.trackoff.ui;

import com.trackoff.io.PreviewLookup;
import com.trackoff.io.lastfm.LastFmClient;
import com.trackoff.io.lastfm.ArtistPlaycountStore;
import com.trackoff.io.lastfm.LastFmPlaycountLookup;
import com.trackoff.io.lastfm.PlaycountStore;
import com.trackoff.io.lastfm.PlaycountUpdater;
import com.trackoff.model.Playlist;
import com.trackoff.model.Song;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A playlist's Last.fm play counts, in three interchangeable
 * presentations of the same numbers:
 *   - {@link Mode#LIST} — songs in order with album art, a preview
 *     button, and a bar per track.
 *   - {@link Mode#GRAPH} — {@link PlaycountGraph}, plays against
 *     playlist position, for seeing where the listening actually sits.
 *   - {@link Mode#ARTISTS} — {@link ArtistBlocksView}, the playlist
 *     regrouped by artist and sortable by track count, plays within
 *     this playlist, or all-time plays for the artist.
 * All three read from the same {@code rows} list, so they can't
 * disagree, and a finished update refreshes whichever is on screen.
 *
 * Play counts come from {@link PlaycountStore} (the {@code songs} table),
 * so opening this screen is a single local query and everything renders
 * filled-in immediately. This used to re-fetch the entire playlist from
 * Last.fm on every open: two rate-limited API calls per song, several
 * minutes of bars trickling in for a big playlist, repeated in full
 * every time the screen was opened. Refreshing is now something the user
 * asks for — "Update Last.fm plays" — and it runs in the background via
 * {@link PlaycountUpdater}, applying its results in one pass when it
 * finishes. The job is registered per playlist, so navigating away and
 * back re-attaches to the running update rather than starting a second.
 *
 * Preview clips stay lazy via {@link PreviewLookup} (the same
 * Deezer-backed lookup ComparisonView/SwipeView use), resolved only when
 * a row's play button is clicked. Song membership and order were already
 * persisted by the caller (see {@code com.trackoff.db.PlaylistPersistence})
 * before this view opens.
 */
public final class LastFmManagerView {

    /** Which presentation is currently in the body slot of {@link #root}. */
    public enum Mode {
        LIST("List"), GRAPH("Graph"), ARTISTS("Artists");

        final String label;
        Mode(String label) { this.label = label; }
    }

    private static final double MAX_BAR_WIDTH = 200;
    private static final double BAR_HEIGHT    = 8;
    private static final double ART_SIZE      = 48;

    private final Stage    stage;
    private final String   playlistId;
    private final Playlist playlist;

    /** Always in original playlist order; {@link #renderRows} controls display order. */
    private final List<RowState> rows = new ArrayList<>();
    private VBox rowsBox;
    private ScrollPane listScroll;
    private PlaycountGraph graph;
    private VBox root;
    private ArtistBlocksView artistsView;
    private Mode mode = Mode.LIST;
    private final Map<Mode, Button> modeButtons = new EnumMap<>(Mode.class);
    private boolean sortByPlays = false;
    /** Set while a cancelled update drains its in-flight lookups; suppresses stale progress. */
    private boolean stopping = false;
    private long runningMax = 0;
    private RowState currentlyPlaying;
    private Set<String> initiallyOverridden = Set.of();

    private Label  statusLabel;
    private Button updateBtn;

    private Button sortBtn;

    /** Per-row UI refs + playback state, one per song. */
    private static final class RowState {
        final Song song;
        final Region fillBar;
        final Label countLabel;
        final Button playBtn;
        final Label indexLabel;
        HBox rowNode;
        long playcount;
        /** True once this song has a real play count (cached or freshly fetched). */
        boolean resolved;
        MediaPlayer player;

        RowState(Song song, Region fillBar, Label countLabel, Button playBtn, Label indexLabel) {
            this.song = song;
            this.fillBar = fillBar;
            this.countLabel = countLabel;
            this.playBtn = playBtn;
            this.indexLabel = indexLabel;
        }
    }

    public LastFmManagerView(Stage stage, String playlistId, Playlist playlist) {
        this.stage      = stage;
        this.playlistId = playlistId;
        this.playlist   = playlist;
    }

    public void show() {
        Label title = new Label(playlist.name());
        title.getStyleClass().add("label-header");

        statusLabel = new Label();
        statusLabel.getStyleClass().add("label-stats");

        updateBtn = new Button("Update Last.fm plays");
        updateBtn.getStyleClass().add("button-ghost");
        updateBtn.setOnAction(e -> onUpdateClicked());

        HBox modeBar = new HBox(4);
        modeBar.setAlignment(Pos.CENTER_LEFT);
        for (Mode m : Mode.values()) {
            Button b = new Button(m.label);
            b.getStyleClass().add("mode-btn");
            b.setOnAction(e -> setMode(m));
            modeButtons.put(m, b);
            modeBar.getChildren().add(b);
        }

        sortBtn = new Button("Sort: Most Played");
        sortBtn.getStyleClass().add("button-ghost");
        sortBtn.setOnAction(e -> {
            sortByPlays = !sortByPlays;
            sortBtn.setText(sortByPlays ? "Sort: Playlist Order" : "Sort: Most Played");
            applySort();
        });

        Button back = new Button("Back");
        back.getStyleClass().add("button-ghost");
        back.setOnAction(e -> leave());

        Region hspacer = new Region();
        HBox.setHgrow(hspacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, hspacer, updateBtn, modeBar, sortBtn, back);
        header.setAlignment(Pos.CENTER_LEFT);

        initiallyOverridden = LastFmPlaycountLookup.songIdsWithOverride(
                playlist.songs().stream().map(Song::id).collect(Collectors.toSet()));

        rowsBox = new VBox(4);
        for (Song s : playlist.songs()) {
            buildRow(s);
        }
        renderRows(rows);

        listScroll = new ScrollPane(rowsBox);
        listScroll.setFitToWidth(true);
        listScroll.setStyle("-fx-background-color:transparent; -fx-background:transparent;");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        graph = new PlaycountGraph();
        VBox.setVgrow(graph, Priority.ALWAYS);

        artistsView = new ArtistBlocksView();
        VBox.setVgrow(artistsView, Priority.ALWAYS);

        root = new VBox(14, header, statusLabel, listScroll);
        root.setPadding(new Insets(24));
        setMode(Mode.LIST);

        Theme.show(stage, root, 900, 720);
        stage.setTitle("Trackoff — Last.fm Manager — " + playlist.name());
        stage.setOnCloseRequest(e -> detachAndDispose());

        loadCachedCounts();
        attachToRunningUpdate();
    }

    // ==================================================================
    //  Cached play counts — the whole reason this screen opens instantly
    // ==================================================================

    /**
     * Fill every row from the persisted cache in one query. Songs with
     * no cached count are left showing "—" rather than "0 plays": never
     * fetched and confirmed-zero are different facts, and conflating
     * them was the bug that made rate-limited fetches poison the cache
     * in the first place.
     */
    private void loadCachedCounts() {
        List<String> ids = playlist.songs().stream().map(Song::id).toList();
        Map<String, PlaycountStore.Cached> cached = PlaycountStore.load(ids);

        runningMax = 0;
        for (RowState row : rows) {
            PlaycountStore.Cached c = cached.get(row.song.id());
            if (c == null) {
                row.resolved = false;
                row.countLabel.setText("—");
                row.fillBar.setPrefWidth(0);
                continue;
            }
            row.playcount = c.playcount();
            row.resolved  = true;
            row.countLabel.setText(String.format("%,d plays", c.playcount()));
            // Prime the in-memory cache too, so a single-row refresh
            // elsewhere in the session doesn't re-hit the network.
            LastFmPlaycountLookup.seed(row.song, c.playcount());
            runningMax = Math.max(runningMax, c.playcount());
        }

        for (RowState r : rows) rescaleBar(r);
        if (sortByPlays) applySort();
        refreshDerivedViews();
        updateStatus(PlaycountStore.newestFetchedAt(cached).orElse(null));
    }

    private void updateStatus(String newestFetchedAt) {
        long missing = rows.stream().filter(r -> !r.resolved).count();
        String base = playlist.size() + " tracks  ·  Last.fm play counts";

        if (newestFetchedAt == null) {
            statusLabel.setText(base + "  ·  never fetched — press \"Update Last.fm plays\"");
        } else if (missing > 0) {
            statusLabel.setText(base + "  ·  updated " + PlaycountStore.humanize(newestFetchedAt)
                    + "  ·  " + missing + " song" + (missing == 1 ? "" : "s") + " without play data");
        } else {
            statusLabel.setText(base + "  ·  updated " + PlaycountStore.humanize(newestFetchedAt));
        }
    }

    // ==================================================================
    //  Background refresh
    // ==================================================================

    private void onUpdateClicked() {
        PlaycountUpdater.Job running = PlaycountUpdater.jobFor(playlistId);
        if (running != null) {
            running.cancel();
            // Songs already in flight still finish (their HTTP calls can't
            // be yanked mid-retry), so progress callbacks keep arriving for
            // a few seconds after this. Without the flag they'd overwrite
            // the button back to "Stop update" and make the click look
            // like it did nothing.
            stopping = true;
            updateBtn.setText("Stopping…");
            updateBtn.setDisable(true);
            statusLabel.setText(playlist.size() + " tracks  ·  stopping — letting in-flight lookups finish…");
            return;
        }
        if (!LastFmClient.isLinked()) {
            info("Connect your Last.fm account first.");
            return;
        }
        stopping = false;
        PlaycountUpdater.start(playlistId, playlist.songs(), updateListener());
        onUpdateProgress(PlaycountUpdater.Phase.SONGS, 0, playlist.size());
    }

    /**
     * Re-attach to an update that's still running from an earlier visit
     * to this screen — the job outlives the view, so coming back should
     * show live progress instead of a stale "Update" button that would
     * start a duplicate run.
     */
    private void attachToRunningUpdate() {
        PlaycountUpdater.Job job = PlaycountUpdater.jobFor(playlistId);
        if (job == null) return;
        job.listenWith(updateListener());
        onUpdateProgress(job.phase(), job.done(), job.total());
    }

    private PlaycountUpdater.Listener updateListener() {
        return new PlaycountUpdater.Listener() {
            @Override public void onProgress(PlaycountUpdater.Phase phase, int done, int total) {
                onUpdateProgress(phase, done, total);
            }
            @Override public void onFinished(PlaycountUpdater.Result result) {
                onUpdateFinished(result);
            }
        };
    }

    private void onUpdateProgress(PlaycountUpdater.Phase phase, int done, int total) {
        if (stopping) return;
        updateBtn.setDisable(false);
        updateBtn.setText("Stop update  (" + done + "/" + total + ")");
        statusLabel.setText(playlist.size() + " tracks  ·  fetching " + phase.label() + " from Last.fm… "
                + done + " of " + total
                + (done < total ? "  (this runs in the background — you can keep browsing)" : ""));
    }

    /**
     * Apply a finished run in one pass. Results are applied wholesale
     * rather than row-by-row as they arrive: a partially-updated list
     * rescales its bars against a moving max, which reads as every bar
     * twitching for the length of the run.
     */
    private void onUpdateFinished(PlaycountUpdater.Result result) {
        for (RowState row : rows) {
            Long fresh = result.songPlays().get(row.song.id());
            if (fresh == null) continue;   // never resolved — keep whatever was cached before
            row.playcount = fresh;
            row.resolved = true;
            row.countLabel.setText(String.format("%,d plays", fresh));
        }

        runningMax = rows.stream().filter(r -> r.resolved).mapToLong(r -> r.playcount).max().orElse(0);
        for (RowState r : rows) rescaleBar(r);
        if (sortByPlays) applySort();
        refreshDerivedViews();

        stopping = false;
        updateBtn.setDisable(false);
        updateBtn.setText("Update Last.fm plays");

        statusLabel.setText(playlist.size() + " tracks  ·  " + finishedMessage(result));
    }

    /**
     * Report what a run actually achieved, not just that it ended.
     * Last.fm throttles sustained bursts hard, and when it does the run
     * comes back with everything failed — reporting that as "updated
     * just now" with a footnote is a lie about the data on screen, which
     * is still the previous fetch's.
     */
    private static String finishedMessage(PlaycountUpdater.Result result) {
        int failed = result.failed();
        int updated = result.updated();

        if (result.cancelled()) {
            return "update stopped — " + result.songPlays().size() + " track"
                    + (result.songPlays().size() == 1 ? "" : "s") + " and "
                    + result.artistPlays().size() + " artist"
                    + (result.artistPlays().size() == 1 ? "" : "s") + " updated";
        }
        if (updated == 0 && failed > 0) {
            return "update failed — Last.fm answered none of the " + failed
                    + " lookups. It throttles bursts; wait a few minutes and try again.";
        }
        if (failed > 0) {
            return "updated just now  ·  " + failed + " of " + (updated + failed)
                    + " lookups failed (Last.fm throttling) — run it again to fill the gaps";
        }
        return "updated just now";
    }

    /** Detach from any running update (it keeps going and keeps saving) and leave the screen. */
    private void leave() {
        detachAndDispose();
        new MainView(stage).show();
    }

    /**
     * Stop receiving callbacks for this playlist's update and release
     * media players. The job itself deliberately survives: it keeps
     * fetching and keeps writing to {@link PlaycountStore}, so whatever
     * it finishes is waiting in the cache next time the screen opens.
     */
    private void detachAndDispose() {
        PlaycountUpdater.Job job = PlaycountUpdater.jobFor(playlistId);
        if (job != null) job.listenWith(null);
        disposeAllPlayers();
    }

    // ==================================================================
    //  View modes — three presentations of one set of play counts.
    //  All three read from `rows`, so they never disagree and a finished
    //  update refreshes whichever one is on screen.
    // ==================================================================

    private void setMode(Mode mode) {
        this.mode = mode;
        for (Map.Entry<Mode, Button> e : modeButtons.entrySet()) {
            e.getValue().getStyleClass().remove("mode-btn-active");
            if (e.getKey() == mode) e.getValue().getStyleClass().add("mode-btn-active");
        }
        // Only the list has a playlist-order/most-played toggle: the graph
        // is always in playlist order (that's what its Y axis means) and
        // the Artists view has its own sort control.
        sortBtn.setDisable(mode != Mode.LIST);

        Node body = switch (mode) {
            case LIST    -> listScroll;
            case GRAPH   -> graph;
            case ARTISTS -> artistsView;
        };
        root.getChildren().set(2, body);
        refreshDerivedViews();
    }

    /** Push the current play counts into whichever alternative view is showing. */
    private void refreshDerivedViews() {
        if (mode == Mode.GRAPH)   refreshGraphData();
        if (mode == Mode.ARTISTS) refreshArtistData();
    }

    private void refreshGraphData() {
        if (graph == null) return;
        List<PlaycountGraph.Bar> bars = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            RowState r = rows.get(i);
            bars.add(new PlaycountGraph.Bar(
                    i + 1, r.song.title(), r.song.artist(), r.playcount, r.resolved));
        }
        graph.setData(bars);
    }

    /**
     * Regroup the playlist by artist. Grouping uses the same primary-artist
     * rule the play-count lookup uses ({@link LastFmPlaycountLookup#primaryArtist}),
     * so a song can't land under an artist whose all-time count was
     * fetched for a differently-spelled credit.
     */
    private void refreshArtistData() {
        if (artistsView == null) return;

        // Insertion-ordered so an artist's tracks stay in playlist order.
        Map<String, List<Integer>> byArtist = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String display = LastFmPlaycountLookup.primaryArtist(rows.get(i).song.artist());
            String key = ArtistPlaycountStore.key(display);
            if (key.isEmpty()) continue;
            byArtist.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            displayNames.putIfAbsent(key, display);
        }

        Map<String, ArtistPlaycountStore.Cached> artistCounts =
                ArtistPlaycountStore.load(byArtist.keySet());

        List<ArtistBlocksView.ArtistBlock> blocks = new ArrayList<>(byArtist.size());
        for (Map.Entry<String, List<Integer>> e : byArtist.entrySet()) {
            List<ArtistBlocksView.TrackEntry> tracks = new ArrayList<>(e.getValue().size());
            long playlistPlays = 0;
            int withPlays = 0;
            for (int idx : e.getValue()) {
                RowState r = rows.get(idx);
                tracks.add(new ArtistBlocksView.TrackEntry(
                        idx + 1, r.song.title(), r.playcount, r.resolved));
                if (r.resolved) { playlistPlays += r.playcount; withPlays++; }
            }

            ArtistPlaycountStore.Cached cached = artistCounts.get(e.getKey());
            blocks.add(new ArtistBlocksView.ArtistBlock(
                    displayNames.get(e.getKey()), tracks,
                    playlistPlays, withPlays,
                    cached == null ? 0 : cached.playcount(),
                    cached != null));
        }

        artistsView.setData(blocks);
    }

    // ==================================================================

    private void buildRow(Song s) {
        Label indexLabel = new Label();
        indexLabel.getStyleClass().add("label-song-meta");
        indexLabel.setMinWidth(24);

        // ----- Album art + play-button overlay -----
        ImageView art = new ImageView();
        art.setFitWidth(ART_SIZE);
        art.setFitHeight(ART_SIZE);
        art.setPreserveRatio(false);
        art.setSmooth(true);
        if (s.hasImage()) {
            try {
                art.setImage(new Image(s.imageUrl(), ART_SIZE, ART_SIZE, false, true, true));
            } catch (Exception ignored) { /* leave blank */ }
        }
        Rectangle artClip = new Rectangle(ART_SIZE, ART_SIZE);
        artClip.setArcWidth(8);
        artClip.setArcHeight(8);
        art.setClip(artClip);

        Button playBtn = new Button("▶");
        playBtn.getStyleClass().add("tile-play-overlay");

        StackPane artHolder = new StackPane(art, playBtn);
        artHolder.setMinSize(ART_SIZE, ART_SIZE);
        artHolder.setMaxSize(ART_SIZE, ART_SIZE);

        // ----- Title / artist -----
        Label titleLabel  = new Label(s.title());
        Label artistLabel = new Label(s.artist());
        titleLabel.getStyleClass().add("label-song-title");
        artistLabel.getStyleClass().add("label-song-artist");
        titleLabel.setStyle("-fx-font-size: 15px;");
        VBox info = new VBox(2, titleLabel, artistLabel);
        info.setMinWidth(200);
        info.setMaxWidth(300);
        HBox.setHgrow(info, Priority.ALWAYS);

        // ----- Play-count bar -----
        Region track = new Region();
        track.getStyleClass().add("playcount-bar-track");
        track.setMinSize(MAX_BAR_WIDTH, BAR_HEIGHT);
        track.setPrefSize(MAX_BAR_WIDTH, BAR_HEIGHT);
        track.setMaxSize(MAX_BAR_WIDTH, BAR_HEIGHT);

        Region fill = new Region();
        fill.getStyleClass().add("playcount-bar-fill");
        fill.setMinSize(0, BAR_HEIGHT);
        fill.setPrefSize(0, BAR_HEIGHT);
        // Plain Regions default to an unbounded max width, so StackPane
        // stretches them to fill the whole track — cap max to pref so
        // setPrefWidth() below actually controls the rendered width.
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        fill.setMaxHeight(BAR_HEIGHT);

        StackPane barStack = new StackPane(track, fill);
        barStack.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        barStack.setMinWidth(MAX_BAR_WIDTH);

        Label countLabel = new Label("…");
        countLabel.getStyleClass().add("label-song-meta");
        countLabel.setMinWidth(70);

        RowState state = new RowState(s, fill, countLabel, playBtn, indexLabel);
        playBtn.setOnAction(e -> togglePlayback(state));
        if (initiallyOverridden.contains(s.id())) {
            countLabel.getStyleClass().add("label-manual-override");
        }

        HBox row = new HBox(14, indexLabel, artHolder, info, barStack, countLabel);
        row.getStyleClass().add("lastfm-manager-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 10, 6, 10));

        MenuItem reassignItem = new MenuItem("Set Last.fm track…");
        reassignItem.setOnAction(e -> showReassignDialog(state));
        MenuItem clearItem = new MenuItem("Clear manual override");
        clearItem.setOnAction(e -> clearOverride(state));
        ContextMenu menu = new ContextMenu(reassignItem, clearItem);
        row.setOnContextMenuRequested(e -> menu.show(row, e.getScreenX(), e.getScreenY()));

        state.rowNode = row;
        rows.add(state);
    }

    // ==================================================================
    //  Manual reassignment — right-click a row and paste a Last.fm
    //  track URL to override which track its play count comes from.
    //  Persisted per-song via LastFmPlaycountLookup, so it survives
    //  restarts and applies wherever this song appears.
    // ==================================================================

    private void showReassignDialog(RowState row) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Reassign Last.fm track");
        dialog.setHeaderText("Paste the Last.fm track URL for:\n" + row.song.title() + " — " + row.song.artist());
        dialog.setContentText("Last.fm URL:");
        Theme.apply(dialog.getDialogPane().getScene());

        dialog.showAndWait().ifPresent(url -> {
            if (url.isBlank()) return;
            Optional<LastFmClient.TrackMatch> match = LastFmClient.parseTrackUrl(url);
            if (match.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.ERROR,
                        "Couldn't read a track from that URL. Expected something like "
                      + "https://www.last.fm/music/Artist/_/Track+Name");
                Theme.apply(a.getDialogPane().getScene());
                a.showAndWait();
                return;
            }
            LastFmPlaycountLookup.setOverride(row.song.id(), match.get().artist(), match.get().name());
            row.countLabel.getStyleClass().add("label-manual-override");
            refreshRow(row);
        });
    }

    private void clearOverride(RowState row) {
        LastFmPlaycountLookup.clearOverride(row.song.id());
        row.countLabel.getStyleClass().remove("label-manual-override");
        refreshRow(row);
    }

    /**
     * Re-fetch exactly one song, right now. This is the one place a
     * network call still happens without the user pressing "Update":
     * they just told us the old number was attributed to the wrong
     * track, so it's a single deliberate lookup, not a bulk refresh.
     */
    private void refreshRow(RowState row) {
        LastFmPlaycountLookup.invalidate(row.song);
        row.resolved = false;
        row.countLabel.setText("…");
        LastFmPlaycountLookup.resolveAsync(row.song, playcount -> onPlaycountResolved(row, playcount));
    }

    // ==================================================================
    //  Sorting — "Playlist Order" (insertion order) vs. "Most Played"
    //  (descending playcount; re-applied live as more counts resolve).
    // ==================================================================

    private void applySort() {
        List<RowState> order;
        if (sortByPlays) {
            order = new ArrayList<>(rows);
            order.sort((a, b) -> Long.compare(b.playcount, a.playcount));   // stable: ties keep playlist order
        } else {
            order = rows;
        }
        renderRows(order);
    }

    private void renderRows(List<RowState> order) {
        List<Node> nodes = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            RowState r = order.get(i);
            r.indexLabel.setText(String.valueOf(i + 1));
            nodes.add(r.rowNode);
        }
        rowsBox.getChildren().setAll(nodes);
    }

    // ==================================================================
    //  Play counts
    // ==================================================================

    private void onPlaycountResolved(RowState row, long playcount) {
        if (playcount == LastFmPlaycountLookup.FAILED) {
            // Genuine failure even after LastFmClient's built-in rate-limit
            // retries — show this honestly rather than as a confirmed 0,
            // and don't let it factor into the max-play bar scaling or
            // count as "0 plays" if sorted by most played.
            row.countLabel.setText("—");
            return;
        }

        row.playcount = playcount;
        row.resolved = true;
        row.countLabel.setText(String.format("%,d plays", playcount));

        if (playcount > runningMax) {
            runningMax = playcount;
            for (RowState r : rows) rescaleBar(r);
        } else {
            rescaleBar(row);
        }

        if (sortByPlays) applySort();
        refreshDerivedViews();
    }

    private void rescaleBar(RowState row) {
        if (!row.resolved) { row.fillBar.setPrefWidth(0); return; }
        double width;
        if (runningMax <= 0 || row.playcount <= 0) {
            width = 0;
        } else {
            width = Math.max(4, (row.playcount / (double) runningMax) * MAX_BAR_WIDTH);
        }
        row.fillBar.setPrefWidth(width);
    }

    // ==================================================================
    //  Preview playback — lazy: resolved on first click of a row's
    //  play button, same Deezer-backed lookup as ComparisonView/SwipeView.
    // ==================================================================

    private void togglePlayback(RowState row) {
        if (currentlyPlaying != null && currentlyPlaying != row && currentlyPlaying.player != null) {
            currentlyPlaying.player.pause();
            currentlyPlaying.playBtn.setText("▶");
        }

        if (row.player != null) {
            if (row.player.getStatus() == MediaPlayer.Status.PLAYING) {
                row.player.pause();
                row.playBtn.setText("▶");
                currentlyPlaying = null;
            } else {
                row.player.seek(Duration.ZERO);
                row.player.play();
                row.playBtn.setText("⏸");
                currentlyPlaying = row;
            }
            return;
        }

        row.playBtn.setDisable(true);
        PreviewLookup.resolveAsync(row.song, previewUrl -> {
            row.playBtn.setDisable(false);
            if (previewUrl.isEmpty()) return;   // no preview found; button stays inert

            try {
                Media media = new Media(previewUrl.get());
                MediaPlayer player = new MediaPlayer(media);
                player.setVolume(0.7);
                player.setOnEndOfMedia(() -> {
                    player.stop();
                    row.playBtn.setText("▶");
                    if (currentlyPlaying == row) currentlyPlaying = null;
                });
                row.player = player;
                player.play();
                row.playBtn.setText("⏸");
                currentlyPlaying = row;
            } catch (Exception ignored) {
                // Best-effort, same as elsewhere: a bad media URL just means no playback.
            }
        });
    }

    private void disposeAllPlayers() {
        for (RowState r : rows) {
            if (r.player != null) { r.player.dispose(); r.player = null; }
        }
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg);
        Theme.apply(a.getDialogPane().getScene());
        a.showAndWait();
    }
}
