package com.trackoff.ui;

import com.trackoff.model.Playlist;
import com.trackoff.model.Song;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tinder-style swipe view. Drag the card left to delete a song from the
 * playlist, right to keep it — or use the buttons / arrow keys. When
 * every song has been decided we show a summary with both piles and a
 * CSV export.
 *
 * If a song carries a 30-second preview URL (only Spotify imports do),
 * that clip auto-plays while the card is on top.
 */
public final class SwipeView {

    private static final double CARD_WIDTH      = 380;
    private static final double CARD_HEIGHT     = 560;
    private static final double ART_SIZE        = 340;
    private static final double SWIPE_THRESHOLD = 140;   // px past origin to commit

    private final Stage      stage;
    private final Playlist   playlist;
    private final List<Song> songs;

    // ---- Decision state ----
    private int currentIndex = 0;
    private final List<Song>     kept    = new ArrayList<>();
    private final List<Song>     deleted = new ArrayList<>();
    /** Chronological log so Undo can pop the last decision. */
    private final List<Decision> history = new ArrayList<>();

    private record Decision(Song song, boolean kept) {}

    // ---- UI refs ----
    private StackPane card;
    private ImageView artView;
    private Label     titleLabel;
    private Label     artistLabel;
    private Label     albumLabel;
    private Label     progressLabel;
    private Label     keepOverlay;
    private Label     deleteOverlay;
    private Button    undoBtn;

    // ---- Drag state ----
    private double dragStartX;
    private double dragStartY;

    // ---- Preview player ----
    private MediaPlayer currentPlayer;

    // ---- Image cache so we don't re-download on undo ----
    private final Map<String, Image> imageCache = new HashMap<>();

    public SwipeView(Stage stage, Playlist playlist, List<Song> songs) {
        this.stage    = stage;
        this.playlist = playlist;
        this.songs    = new ArrayList<>(songs);
    }

    // ==================================================================
    //  Scene assembly
    // ==================================================================
    public void show() {
        if (songs.isEmpty()) { new MainView(stage).show(); return; }

        Label title = new Label("Swipe — " + playlist.name());
        title.getStyleClass().add("label-header");

        progressLabel = new Label();
        progressLabel.getStyleClass().add("label-muted");

        Region hs = new Region();
        HBox.setHgrow(hs, Priority.ALWAYS);

        Button back = ghost("Back");
        back.setOnAction(e -> { stopPreview(); new MainView(stage).show(); });

        HBox header = new HBox(12, title, progressLabel, hs, back);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- Card ----
        card = buildCard();
        StackPane cardContainer = new StackPane(card);
        cardContainer.setPrefSize(CARD_WIDTH + 40, CARD_HEIGHT + 40);
        cardContainer.setPadding(new Insets(20));

        // ---- Bottom action bar ----
        Button deleteBtn = new Button("✕  Delete");
        deleteBtn.getStyleClass().add("button-danger");
        deleteBtn.setOnAction(e -> animateSwipe(-1));

        undoBtn = ghost("↶  Undo");
        undoBtn.setDisable(true);
        undoBtn.setOnAction(e -> undo());

        Button keepBtn = new Button("♥  Keep");
        keepBtn.getStyleClass().add("button-primary");
        keepBtn.setOnAction(e -> animateSwipe(+1));

        HBox actions = new HBox(16, deleteBtn, undoBtn, keepBtn);
        actions.setAlignment(Pos.CENTER);
        actions.setPadding(new Insets(8, 0, 0, 0));

        Label hint = new Label("Drag the card, or use ← / → arrows.  Z undoes.");
        hint.getStyleClass().add("label-muted");

        VBox root = new VBox(14, header, cardContainer, actions, hint);
        root.setAlignment(Pos.TOP_CENTER);
        root.setPadding(new Insets(24));

        Scene scene = new Scene(root, 720, 820);
        Theme.apply(scene);

        // Keyboard shortcuts (defer install so focus is on the scene, not a button).
        scene.setOnKeyPressed(e -> {
            if      (e.getCode() == KeyCode.LEFT)  animateSwipe(-1);
            else if (e.getCode() == KeyCode.RIGHT) animateSwipe(+1);
            else if (e.getCode() == KeyCode.Z)     undo();
        });

        stage.setScene(scene);
        stage.setTitle("trackoff — Swipe");
        loadCurrent();
    }

    // ==================================================================
    //  Card construction (built once; contents swapped per song)
    // ==================================================================
    private StackPane buildCard() {
        StackPane s = new StackPane();
        s.getStyleClass().add("swipe-card");
        s.setPrefSize(CARD_WIDTH, CARD_HEIGHT);
        s.setMaxSize(CARD_WIDTH, CARD_HEIGHT);

        // Round the corners of the card itself.
        Rectangle clip = new Rectangle(CARD_WIDTH, CARD_HEIGHT);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        s.setClip(clip);

        // Art
        artView = new ImageView();
        artView.setFitWidth(ART_SIZE);
        artView.setFitHeight(ART_SIZE);
        artView.setPreserveRatio(false);
        artView.setSmooth(true);

        Rectangle artClip = new Rectangle(ART_SIZE, ART_SIZE);
        artClip.setArcWidth(16);
        artClip.setArcHeight(16);
        artView.setClip(artClip);

        titleLabel  = new Label();  titleLabel .getStyleClass().add("swipe-title");
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(CARD_WIDTH - 40);
        titleLabel.setAlignment(Pos.CENTER);

        artistLabel = new Label();  artistLabel.getStyleClass().add("swipe-artist");
        albumLabel  = new Label();  albumLabel .getStyleClass().add("swipe-album");

        VBox info = new VBox(6, titleLabel, artistLabel, albumLabel);
        info.setAlignment(Pos.CENTER);
        info.setPadding(new Insets(8, 12, 0, 12));

        VBox body = new VBox(14, artView, info);
        body.setAlignment(Pos.TOP_CENTER);
        body.setPadding(new Insets(20));
        s.getChildren().add(body);

        // Overlay stamps ("KEEP" / "NOPE").
        keepOverlay   = stamp("KEEP", "swipe-overlay-keep",   -14);
        deleteOverlay = stamp("NOPE", "swipe-overlay-delete", +14);
        StackPane.setAlignment(keepOverlay,   Pos.TOP_LEFT);
        StackPane.setAlignment(deleteOverlay, Pos.TOP_RIGHT);
        StackPane.setMargin(keepOverlay,   new Insets(28, 0, 0, 28));
        StackPane.setMargin(deleteOverlay, new Insets(28, 28, 0, 0));
        s.getChildren().addAll(keepOverlay, deleteOverlay);

        // Drag mechanics
        s.setOnMousePressed (this::onDragStart);
        s.setOnMouseDragged (this::onDrag);
        s.setOnMouseReleased(this::onDragEnd);

        return s;
    }

    private Label stamp(String text, String extraClass, double rotate) {
        Label l = new Label(text);
        l.getStyleClass().addAll("swipe-overlay-label", extraClass);
        l.setRotate(rotate);
        l.setOpacity(0);
        l.setMouseTransparent(true);
        return l;
    }

    // ==================================================================
    //  Drag handlers
    // ==================================================================
    private void onDragStart(MouseEvent e) {
        dragStartX = e.getSceneX();
        dragStartY = e.getSceneY();
    }

    private void onDrag(MouseEvent e) {
        double dx = e.getSceneX() - dragStartX;
        double dy = e.getSceneY() - dragStartY;
        card.setTranslateX(dx);
        card.setTranslateY(dy * 0.35);
        card.setRotate(dx * 0.06);

        double t = Math.min(1.0, Math.abs(dx) / SWIPE_THRESHOLD);
        if (dx > 0) { keepOverlay.setOpacity(t);   deleteOverlay.setOpacity(0); }
        else        { deleteOverlay.setOpacity(t); keepOverlay  .setOpacity(0); }
    }

    private void onDragEnd(MouseEvent e) {
        double dx = e.getSceneX() - dragStartX;
        if (Math.abs(dx) >= SWIPE_THRESHOLD) animateSwipe(dx > 0 ? +1 : -1);
        else snapBack();
    }

    // ==================================================================
    //  Snap-back and swipe-off animations
    // ==================================================================
    private void snapBack() {
        TranslateTransition tt = new TranslateTransition(Duration.millis(180), card);
        tt.setToX(0); tt.setToY(0);
        RotateTransition    rt = new RotateTransition(Duration.millis(180), card);
        rt.setToAngle(0);
        FadeTransition fk = new FadeTransition(Duration.millis(140), keepOverlay);   fk.setToValue(0);
        FadeTransition fd = new FadeTransition(Duration.millis(140), deleteOverlay); fd.setToValue(0);
        new ParallelTransition(tt, rt, fk, fd).play();
    }

    private void animateSwipe(int direction) {
        if (currentIndex >= songs.size() || card.isDisabled()) return;
        card.setDisable(true);   // block extra input mid-flight

        // Slam the overlay to full for button/keyboard-triggered swipes.
        if (direction > 0) { keepOverlay.setOpacity(1); deleteOverlay.setOpacity(0); }
        else               { deleteOverlay.setOpacity(1); keepOverlay.setOpacity(0); }

        double flyTo = direction > 0 ? 900 : -900;

        TranslateTransition tt = new TranslateTransition(Duration.millis(280), card);
        tt.setToX(flyTo);
        RotateTransition rt = new RotateTransition(Duration.millis(280), card);
        rt.setToAngle(direction * 22);
        FadeTransition ft = new FadeTransition(Duration.millis(280), card);
        ft.setToValue(0);

        ParallelTransition pt = new ParallelTransition(tt, rt, ft);
        pt.setOnFinished(ev -> {
            commitDecision(direction > 0);
            currentIndex++;
            if (currentIndex >= songs.size()) {
                showSummary();
            } else {
                resetCardTransforms();
                loadCurrent();
                card.setDisable(false);
            }
        });
        pt.play();
    }

    private void commitDecision(boolean keep) {
        Song s = songs.get(currentIndex);
        (keep ? kept : deleted).add(s);
        history.add(new Decision(s, keep));
        undoBtn.setDisable(false);
    }

    private void resetCardTransforms() {
        card.setTranslateX(0);
        card.setTranslateY(0);
        card.setRotate(0);
        card.setOpacity(1);
        keepOverlay  .setOpacity(0);
        deleteOverlay.setOpacity(0);
    }

    // ==================================================================
    //  Undo — slides the previous card back in from its swipe-off side.
    // ==================================================================
    private void undo() {
        if (history.isEmpty()) return;
        Decision last = history.remove(history.size() - 1);
        (last.kept() ? kept : deleted).remove(last.song());
        currentIndex--;
        undoBtn.setDisable(history.isEmpty());

        double fromX = last.kept() ? 900 : -900;
        card.setDisable(true);
        card.setTranslateX(fromX);
        card.setRotate(last.kept() ? 22 : -22);
        card.setOpacity(1);
        keepOverlay  .setOpacity(0);
        deleteOverlay.setOpacity(0);
        loadCurrent();

        TranslateTransition tt = new TranslateTransition(Duration.millis(240), card);
        tt.setToX(0);
        RotateTransition rt = new RotateTransition(Duration.millis(240), card);
        rt.setToAngle(0);
        ParallelTransition pt = new ParallelTransition(tt, rt);
        pt.setOnFinished(ev -> card.setDisable(false));
        pt.play();
    }

    // ==================================================================
    //  Populate the (already-built) card with the song at currentIndex
    // ==================================================================
    private void loadCurrent() {
        if (currentIndex >= songs.size()) return;
        Song s = songs.get(currentIndex);

        progressLabel.setText((currentIndex + 1) + " / " + songs.size());
        titleLabel .setText(s.title());
        artistLabel.setText(s.artist());
        albumLabel .setText(s.album() == null ? "" : s.album());

        if (s.hasImage()) {
            artView.setImage(imageCache.computeIfAbsent(s.id(),
                    k -> new Image(s.imageUrl(), ART_SIZE, ART_SIZE, false, true, true)));
        } else {
            artView.setImage(null);
        }
        startPreview(s);
    }

    // ==================================================================
    //  Preview playback — auto-plays whenever a preview URL exists
    //  (i.e. Spotify imports). CSV playlists have no URL, so silence.
    // ==================================================================
    private void startPreview(Song s) {
        stopPreview();
        if (!s.hasPreview()) return;
        try {
            Media m = new Media(s.previewUrl());
            currentPlayer = new MediaPlayer(m);
            currentPlayer.setVolume(0.5);
            currentPlayer.setAutoPlay(true);
        } catch (Exception ignored) {
            currentPlayer = null;
        }
    }

    private void stopPreview() {
        if (currentPlayer != null) {
            try { currentPlayer.stop(); currentPlayer.dispose(); } catch (Exception ignored) {}
            currentPlayer = null;
        }
    }

    // ==================================================================
    //  Summary screen
    // ==================================================================
    private void showSummary() {
        stopPreview();

        Label title = new Label("Swipe complete — " + playlist.name());
        title.getStyleClass().add("label-header");

        Label kSub = new Label("Kept  (" + kept.size() + ")");
        Label dSub = new Label("Deleted  (" + deleted.size() + ")");
        kSub.getStyleClass().add("label-section");
        dSub.getStyleClass().add("label-section");

        ListView<String> kList = new ListView<>();
        for (Song s : kept)    kList.getItems().add(s.title() + "  —  " + s.artist());
        ListView<String> dList = new ListView<>();
        for (Song s : deleted) dList.getItems().add(s.title() + "  —  " + s.artist());

        VBox kBox = new VBox(6, kSub, kList);
        VBox dBox = new VBox(6, dSub, dList);
        HBox.setHgrow(kBox, Priority.ALWAYS);
        HBox.setHgrow(dBox, Priority.ALWAYS);
        VBox.setVgrow(kList, Priority.ALWAYS);
        VBox.setVgrow(dList, Priority.ALWAYS);

        HBox lists = new HBox(18, kBox, dBox);
        VBox.setVgrow(lists, Priority.ALWAYS);

        Button exportBtn = new Button("Export CSV");
        exportBtn.getStyleClass().add("button-primary");
        exportBtn.setOnAction(e -> exportCsv());

        Button restart = ghost("Swipe again");
        restart.setOnAction(e -> {
            currentIndex = 0;
            kept.clear();
            deleted.clear();
            history.clear();
            show();
        });

        Button back = ghost("Back to menu");
        back.setOnAction(e -> new MainView(stage).show());

        HBox actions = new HBox(12, exportBtn, restart, back);

        VBox root = new VBox(16, title, lists, actions);
        root.setPadding(new Insets(24));

        Scene scene = new Scene(root, 900, 640);
        Theme.apply(scene);
        stage.setScene(scene);
    }

    private void exportCsv() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export swipe results");
        fc.setInitialFileName(playlist.name() + " (swipe).csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(file.getAbsolutePath())))) {
            out.println("Decision,Song,Artist,Album,Spotify Track Id");
            for (Song s : kept)    writeRow(out, "Kept",    s);
            for (Song s : deleted) writeRow(out, "Deleted", s);

            Alert a = new Alert(Alert.AlertType.INFORMATION, "Exported to " + file.getName());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        } catch (Exception ex) {
            Alert a = new Alert(Alert.AlertType.ERROR, "Export failed: " + ex.getMessage());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        }
    }

    private void writeRow(PrintWriter out, String decision, Song s) {
        out.println(String.join(",",
                csv(decision), csv(s.title()), csv(s.artist()),
                csv(s.album()), csv(s.id())));
    }

    private String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private Button ghost(String t) {
        Button b = new Button(t);
        b.getStyleClass().add("button-ghost");
        return b;
    }
}