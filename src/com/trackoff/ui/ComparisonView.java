package com.trackoff.ui;

import com.trackoff.io.PreviewLookup;
import com.trackoff.io.ProgressStore;
import com.trackoff.model.Playlist;
import com.trackoff.model.Song;
import com.trackoff.ranking.AdaptiveMergeSortRanker;
import com.trackoff.ranking.ComparisonChoice;
import com.trackoff.ranking.ComparisonRequest;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;



/**
 * The main comparison screen: two cards, four choices, a save button,
 * plus a togglable sidebar showing the ranker's current internal order.
 * When album art is available we render it, and a play/pause button for
 * a 30-second preview clip looked up live from Deezer (works regardless
 * of whether the song came from Spotify or a CSV import).
 */
public final class ComparisonView {

    private final Stage    stage;
    private final Playlist playlist;
    private final AdaptiveMergeSortRanker ranker;

    // Card widgets — one set per side.
    private final CardWidgets left  = new CardWidgets();
    private final CardWidgets right = new CardWidgets();

    // Global UI bits.
    private final Label       headerLabel = new Label("Which do you prefer?");
    private final Label       stats       = new Label();
    private final ProgressBar progress    = new ProgressBar(0);

    private Button undoBtn;
    private VBox   sidebar;
    private Button toggleSidebarBtn;
    private ListView<String> rankingList;
    private final ObservableList<String> rankingItems = FXCollections.observableArrayList();

    /** Media players for the two preview clips (nullable when no preview exists). */
    private MediaPlayer leftPlayer;
    private MediaPlayer rightPlayer;

    /** Optional metadata about where the playlist came from — recorded in the
     *  saved session file so the resume flow can rebuild the playlist. */
    private ProgressStore.SessionMeta sessionMeta;

    /** Called by the launcher (LibraryView or MainView) after construction. */
    public ComparisonView withSessionMeta(ProgressStore.SessionMeta meta) {
        this.sessionMeta = meta;
        return this;
    }

    public ComparisonView(Stage stage, Playlist playlist, AdaptiveMergeSortRanker ranker) {
        this.stage    = stage;
        this.playlist = playlist;
        this.ranker   = ranker;
    }

    // ==================================================================
    //  Scene assembly
    // ==================================================================
    public void show() {
        headerLabel.getStyleClass().add("label-header");

        toggleSidebarBtn = ghostButton("Show current rankings ▶");
        toggleSidebarBtn.setOnAction(e -> toggleSidebar());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(15, headerLabel, headerSpacer, toggleSidebarBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        VBox leftCard  = buildCard(left);
        VBox rightCard = buildCard(right);
        leftCard .setOnMouseClicked(e -> answer(ComparisonChoice.LEFT));
        rightCard.setOnMouseClicked(e -> answer(ComparisonChoice.RIGHT));

        HBox cards = new HBox(20, leftCard, rightCard);
        cards.setAlignment(Pos.CENTER);
        HBox.setHgrow(leftCard,  Priority.ALWAYS);
        HBox.setHgrow(rightCard, Priority.ALWAYS);

        // ---- Choice buttons (grid keeps them evenly sized) ----
        Button pickLeft  = primaryButton("◀ Pick Left");
        Button pickRight = primaryButton("Pick Right ▶");
        Button unknown   = secondaryButton("I don't know one of these");
        Button tie       = secondaryButton("Skip (can't decide)");

        pickLeft.setOnAction (e -> answer(ComparisonChoice.LEFT));
        pickRight.setOnAction(e -> answer(ComparisonChoice.RIGHT));
        unknown.setOnAction  (e -> askWhichUnknown());
        tie.setOnAction      (e -> answer(ComparisonChoice.SKIP_TIE));

        pickLeft.setTooltip (new Tooltip("Left song is preferred"));
        pickRight.setTooltip(new Tooltip("Right song is preferred"));
        unknown.setTooltip  (new Tooltip("Remove unknown song(s) from the final ranking"));
        tie.setTooltip      (new Tooltip("Auto-resolved by popularity (not cached)"));

        GridPane buttonGrid = new GridPane();
        buttonGrid.setHgap(15);
        buttonGrid.setVgap(12);
        ColumnConstraints col = new ColumnConstraints();
        col.setPercentWidth(50);
        col.setHgrow(Priority.ALWAYS);
        buttonGrid.getColumnConstraints().addAll(col, col);
        for (Button b : new Button[]{pickLeft, pickRight, unknown, tie}) {
            b.setPrefHeight(46);
            b.setMaxWidth(Double.MAX_VALUE);
        }
        buttonGrid.add(pickLeft,  0, 0);
        buttonGrid.add(pickRight, 1, 0);
        buttonGrid.add(unknown,   0, 1);
        buttonGrid.add(tie,       1, 1);

        // ---- Bottom bar ----
        stats.getStyleClass().add("label-stats");
        undoBtn = ghostButton("↶ Undo");
        undoBtn.setOnAction(e -> undoLast());
        undoBtn.setDisable(true);
        undoBtn.setTooltip(new Tooltip("Take back your most recent choice (Ctrl+Z)"));

        Button save = ghostButton("Save & Exit");
        save.setOnAction(e -> saveAndExit());

        HBox bottom = new HBox(15, progress, undoBtn, save);
        bottom.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progress, Priority.ALWAYS);
        progress.setMaxWidth(Double.MAX_VALUE);

        Region gap = new Region(); gap.setPrefHeight(6);
        VBox centerContent = new VBox(18, headerRow, cards, buttonGrid, gap, stats, bottom);
        centerContent.setAlignment(Pos.TOP_CENTER);

        // ---- Sidebar ----
        sidebar = buildSidebar();
        sidebar.setVisible(false);
        sidebar.setManaged(false);

        BorderPane root = new BorderPane();
        root.setCenter(centerContent);
        root.setRight(sidebar);
        BorderPane.setMargin(sidebar, new Insets(0, 0, 0, 20));
        root.setPadding(new Insets(30));

        Theme.show(stage, root, 1080, 720);

        // Ctrl+Z → undo. Set on the (possibly reused) scene each time, so
        // it's overwritten rather than stacking handlers from prior views.
        stage.getScene().setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.Z) {
                undoLast();
            }
        });

        // Make sure any playing preview stops if the user closes the window.
        stage.setOnCloseRequest(e -> disposePlayers());

        stage.setTitle("Trackoff — " + playlist.name());

        refresh();
    }

    // ==================================================================
    //  Card widget bundle — makes updating each side symmetric.
    // ==================================================================
    private static final class CardWidgets {
        final ImageView art       = new ImageView();
        final Label     title     = new Label();
        final Label     artist    = new Label();
        final Label     meta      = new Label();
        final Button    playBtn   = new Button("▶ Play preview");
        final StackPane artHolder = new StackPane();

        /** Id of the song currently assigned to this card — lets an
         *  in-flight async preview lookup detect it's stale once the
         *  card has moved on to a different song. */
        String currentSongId;
    }

    /** Build a full card VBox for one side. */
    private VBox buildCard(CardWidgets w) {
        w.title .getStyleClass().add("label-song-title");
        w.artist.getStyleClass().add("label-song-artist");
        w.meta  .getStyleClass().add("label-song-meta");
        w.title.setWrapText(true);
        w.artist.setWrapText(true);
        w.meta.setWrapText(true);

        w.art.setFitWidth(140);
        w.art.setFitHeight(140);
        w.art.setPreserveRatio(true);
        w.art.setSmooth(true);

        Label placeholder = new Label("♪");
        placeholder.setStyle("-fx-font-size: 48px; -fx-text-fill: #4a4a4a;");
        w.artHolder.getChildren().addAll(placeholder, w.art);
        w.artHolder.setStyle("-fx-background-color: #0f0f0f; -fx-background-radius: 6;");
        w.artHolder.setMinSize(140, 140);
        w.artHolder.setMaxSize(140, 140);

        w.playBtn.getStyleClass().add("button-secondary");
        w.playBtn.setVisible(false);   // hidden until we know a preview exists
        w.playBtn.setManaged(false);

        VBox textCol = new VBox(6, w.title, w.artist);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox rightCol = new VBox(8, textCol, spacer, w.playBtn, w.meta);
        HBox.setHgrow(rightCol, Priority.ALWAYS);

        HBox row = new HBox(16, w.artHolder, rightCol);
        row.setAlignment(Pos.TOP_LEFT);

        VBox card = new VBox(row);
        card.getStyleClass().add("song-card");
        card.setMinHeight(220);
        return card;
    }

    // ==================================================================
    //  Sidebar
    // ==================================================================
    private VBox buildSidebar() {
        Label title = new Label("CURRENT ORDER");
        title.getStyleClass().add("label-section");

        Label warning = new Label("Not final until sorting completes");
        warning.getStyleClass().add("label-song-meta");
        warning.setWrapText(true);

        rankingList = new ListView<>(rankingItems);
        rankingList.getStyleClass().add("ranking-list");
        VBox.setVgrow(rankingList, Priority.ALWAYS);

        VBox box = new VBox(8, title, warning, rankingList);
        box.getStyleClass().add("ranking-sidebar");
        box.setPrefWidth(280);
        box.setMinWidth(240);
        return box;
    }

    private void toggleSidebar() {
        boolean show = !sidebar.isVisible();
        sidebar.setVisible(show);
        sidebar.setManaged(show);
        toggleSidebarBtn.setText(show ? "Hide current rankings ◀" : "Show current rankings ▶");
        //stage.setWidth(show ? 1080 : 900);
    }

    private void refreshSidebar() {
        List<Song> current = ranker.finalRanking();
        rankingItems.clear();
        for (int i = 0; i < current.size(); i++) {
            Song s = current.get(i);
            rankingItems.add(String.format("%2d.  %s — %s", i + 1, s.title(), s.artist()));
        }
    }

    // ==================================================================
    //  Actions
    // ==================================================================
    private void answer(ComparisonChoice choice) {
        if (ranker.nextRequest().isEmpty()) return;
        ranker.submit(choice);
        refresh();
    }

    private void undoLast() {
        if (!ranker.canUndo()) return;
        ranker.undo();
        refresh();
    }

    private void askWhichUnknown() {
        if (ranker.nextRequest().isEmpty()) return;
        ComparisonRequest req = ranker.nextRequest().get();

        ButtonType leftBtn  = new ButtonType("Left: "  + req.left().title());
        ButtonType rightBtn = new ButtonType("Right: " + req.right().title());
        ButtonType bothBtn  = new ButtonType("Both");
        ButtonType cancel   = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Remove unknown song");
        dialog.setHeaderText("Which song don't you know?");
        dialog.setContentText("Unknown songs will be removed from your final ranking.");
        dialog.getButtonTypes().setAll(leftBtn, rightBtn, bothBtn, cancel);
        Theme.apply(dialog.getDialogPane().getScene());

        dialog.showAndWait().ifPresent(result -> {
            if      (result == leftBtn)  answer(ComparisonChoice.REMOVE_LEFT);
            else if (result == rightBtn) answer(ComparisonChoice.REMOVE_RIGHT);
            else if (result == bothBtn)  answer(ComparisonChoice.REMOVE_BOTH);
        });
    }

    private void refresh() {
        Optional<ComparisonRequest> next = ranker.nextRequest();
        if (next.isEmpty()) {
            disposePlayers();
            new ResultView(stage, playlist, ranker.finalRanking(), ranker).show();
            return;
        }

        ComparisonRequest req = next.get();
        applySongToCard(req.left(),  left,  true);
        applySongToCard(req.right(), right, false);

        int asked   = ranker.comparisonsAsked();
        int saved   = ranker.comparisonsSavedByInference();
        int estTot  = ranker.estimatedTotalComparisons();
        progress.setProgress(Math.min(1.0, asked / (double) estTot));
        stats.setText(String.format(
                "Comparison %d   •   %d auto-resolved   •   ~%d max",
                asked, saved, estTot));

        undoBtn.setDisable(!ranker.canUndo());
        undoBtn.setText(ranker.canUndo()
                ? "↶ Undo (" + ranker.undoDepth() + ")"
                : "↶ Undo");

        refreshSidebar();
    }

    // ==================================================================
    //  Song → card wiring, including artwork & preview
    // ==================================================================
    private void applySongToCard(Song s, CardWidgets w, boolean isLeft) {
        w.title.setText(s.title());
        w.artist.setText(s.artist());
        w.meta.setText(buildMeta(s));

        // ----- Artwork -----
        if (s.hasImage()) {
            try {
                // backgroundLoading = true so we don't block the UI thread.
                Image img = new Image(s.imageUrl(), 140, 140, true, true, true);
                w.art.setImage(img);
            } catch (Exception ex) {
                w.art.setImage(null);
            }
        } else {
            w.art.setImage(null);
        }

        // ----- Preview -----
        // Dispose whatever was playing for this side and hide the button
        // until (if) an async lookup finds a clip for the new song.
        if (isLeft) {
            if (leftPlayer != null) { leftPlayer.dispose(); leftPlayer = null; }
        } else {
            if (rightPlayer != null) { rightPlayer.dispose(); rightPlayer = null; }
        }
        hidePlayButton(w);

        w.currentSongId = s.id();
        PreviewLookup.resolveAsync(s, previewUrl -> {
            // The card may have moved on to a different song by the time
            // this (async, possibly network-bound) lookup resolves.
            if (!s.id().equals(w.currentSongId) || previewUrl.isEmpty()) return;

            try {
                Media media = new Media(previewUrl.get());
                MediaPlayer newPlayer = new MediaPlayer(media);
                newPlayer.setVolume(0.7);
                // Auto-stop after the clip so the button resets.
                newPlayer.setOnEndOfMedia(() -> {
                    newPlayer.stop();
                    w.playBtn.setText("▶ Play preview");
                });
                w.playBtn.setVisible(true);
                w.playBtn.setManaged(true);
                w.playBtn.setText("▶ Play preview");
                w.playBtn.setOnAction(e -> togglePlayback(w, isLeft));

                if (isLeft) {
                    if (leftPlayer != null) leftPlayer.dispose();
                    leftPlayer = newPlayer;
                } else {
                    if (rightPlayer != null) rightPlayer.dispose();
                    rightPlayer = newPlayer;
                }
            } catch (Exception ex) {
                hidePlayButton(w);
            }
        });
    }

    private void hidePlayButton(CardWidgets w) {
        w.playBtn.setVisible(false);
        w.playBtn.setManaged(false);
    }

    /** Toggle play/pause on one side, pausing the other side automatically. */
    private void togglePlayback(CardWidgets w, boolean isLeft) {
        MediaPlayer self  = isLeft ? leftPlayer  : rightPlayer;
        MediaPlayer other = isLeft ? rightPlayer : leftPlayer;
        CardWidgets otherW = (w == left) ? right : left;

        if (self == null) return;
        if (other != null && other.getStatus() == MediaPlayer.Status.PLAYING) {
            other.pause();
            otherW.playBtn.setText("▶ Play preview");
        }

        if (self.getStatus() == MediaPlayer.Status.PLAYING) {
            self.pause();
            w.playBtn.setText("▶ Play preview");
        } else {
            self.seek(Duration.ZERO);   // always restart from the top for clarity
            self.play();
            w.playBtn.setText("⏸ Pause");
        }
    }

    private void disposePlayers() {
        if (leftPlayer  != null) { leftPlayer.dispose();  leftPlayer  = null; }
        if (rightPlayer != null) { rightPlayer.dispose(); rightPlayer = null; }
    }

    // ==================================================================
    //  Misc helpers
    // ==================================================================
    private String buildMeta(Song s) {
        StringBuilder sb = new StringBuilder();
        if (!s.album().isEmpty())    sb.append(s.album()).append('\n');
        if (s.durationSeconds() > 0) sb.append(s.formattedDuration()).append("   ");
        if (s.bpm() > 0)             sb.append(s.bpm()).append(" BPM   ");
        if (!s.key().isEmpty())      sb.append(s.key()).append("   ");
        if (s.popularity() > 0)      sb.append("\nPopularity: ").append(s.popularity()).append("/100");
        if (s.explicit())            sb.append("   🅴");
        return sb.toString();
    }

    private Button primaryButton(String t)   { Button b = new Button(t); b.getStyleClass().add("button-primary");   return b; }
    private Button secondaryButton(String t) { Button b = new Button(t); b.getStyleClass().add("button-secondary"); return b; }
    private Button ghostButton(String t)     { Button b = new Button(t); b.getStyleClass().add("button-ghost");     return b; }

    private void saveAndExit() {
        disposePlayers();
        FileChooser fc = new FileChooser();
        fc.setTitle("Save session");
        fc.setInitialFileName(playlist.name() + ".trackoff");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Trackoff session", "*.trackoff"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;

        try {
            new ProgressStore().save(Paths.get(file.getAbsolutePath()), ranker, sessionMeta);
            Alert a = new Alert(Alert.AlertType.INFORMATION,
                    "Session saved. Re-open it from the main screen later.");
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
            new MainView(stage).show();
        } catch (Exception ex) {
            Alert a = new Alert(Alert.AlertType.ERROR, "Save failed: " + ex.getMessage());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        }
    }
}