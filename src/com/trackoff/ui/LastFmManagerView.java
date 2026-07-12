package com.trackoff.ui;

import com.trackoff.io.PreviewLookup;
import com.trackoff.io.lastfm.LastFmPlaycountLookup;
import com.trackoff.model.Playlist;
import com.trackoff.model.Song;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
import java.util.List;

/**
 * Shows a playlist's songs in order, each with album art (+ a preview
 * play button overlaid on it) and a bar sized by how many times the
 * linked Last.fm account has played that track.
 *
 * Play counts are looked up live via {@link LastFmPlaycountLookup} as
 * the screen loads; bars fill in progressively and are rescaled against
 * a running max as results arrive, since an early row's bar can't know
 * the eventual max until every song has reported in. Preview clips are
 * resolved lazily via {@link PreviewLookup} (same Deezer-backed lookup
 * used by ComparisonView/SwipeView) only when a row's play button is
 * clicked — fetching audio previews for an entire playlist up front
 * isn't worth it when most rows will never be played. Song membership
 * and order were already persisted by the caller (see
 * {@code com.trackoff.db.PlaylistPersistence}) before this view opens.
 */
public final class LastFmManagerView {

    private static final double MAX_BAR_WIDTH = 200;
    private static final double BAR_HEIGHT    = 8;
    private static final double ART_SIZE      = 48;

    private final Stage    stage;
    private final Playlist playlist;

    private final List<RowState> rows = new ArrayList<>();
    private long runningMax = 0;
    private RowState currentlyPlaying;

    /** Per-row UI refs + playback state, one per song. */
    private static final class RowState {
        final Song song;
        final Region fillBar;
        final Label countLabel;
        final Button playBtn;
        long playcount;
        boolean resolved;
        MediaPlayer player;

        RowState(Song song, Region fillBar, Label countLabel, Button playBtn) {
            this.song = song;
            this.fillBar = fillBar;
            this.countLabel = countLabel;
            this.playBtn = playBtn;
        }
    }

    public LastFmManagerView(Stage stage, String playlistId, Playlist playlist) {
        this.stage    = stage;
        this.playlist = playlist;
    }

    public void show() {
        Label title = new Label(playlist.name());
        title.getStyleClass().add("label-header");

        Label sub = new Label(playlist.size() + " tracks  ·  Last.fm play counts");
        sub.getStyleClass().add("label-muted");

        Button back = new Button("Back");
        back.getStyleClass().add("button-ghost");
        back.setOnAction(e -> { disposeAllPlayers(); new MainView(stage).show(); });

        Region hspacer = new Region();
        HBox.setHgrow(hspacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, hspacer, back);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox rowsBox = new VBox(4);
        for (Song s : playlist.songs()) {
            rowsBox.getChildren().add(buildRow(rows.size(), s));
        }

        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:transparent; -fx-background:transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(14, header, sub, scroll);
        root.setPadding(new Insets(24));

        Scene scene = new Scene(root, 900, 720);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Trackoff — Last.fm Manager — " + playlist.name());
        stage.setOnCloseRequest(e -> disposeAllPlayers());

        for (int i = 0; i < rows.size(); i++) {
            RowState row = rows.get(i);
            LastFmPlaycountLookup.resolveAsync(row.song, playcount -> onPlaycountResolved(row, playcount));
        }
    }

    private HBox buildRow(int index, Song s) {
        Label indexLabel = new Label(String.valueOf(index + 1));
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
        playBtn.getStyleClass().add("playcount-play-overlay");

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

        RowState state = new RowState(s, fill, countLabel, playBtn);
        rows.add(state);
        playBtn.setOnAction(e -> togglePlayback(state));

        HBox row = new HBox(14, indexLabel, artHolder, info, barStack, countLabel);
        row.getStyleClass().add("lastfm-manager-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 10, 6, 10));
        return row;
    }

    // ==================================================================
    //  Play counts
    // ==================================================================

    private void onPlaycountResolved(RowState row, long playcount) {
        row.playcount = playcount;
        row.resolved = true;
        row.countLabel.setText(String.format("%,d plays", playcount));

        if (playcount > runningMax) {
            runningMax = playcount;
            for (RowState r : rows) rescaleBar(r);
        } else {
            rescaleBar(row);
        }
    }

    private void rescaleBar(RowState row) {
        if (!row.resolved) return;
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
}
