package com.trackoff.ui;

import com.trackoff.io.PreviewLookup;
import com.trackoff.model.Playlist;
import com.trackoff.model.Song;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import javafx.embed.swing.SwingFXUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Drag-and-drop tier list builder.
 *
 * Each song is rendered as a small tile: the album cover fills the tile,
 * with a dark gradient across the bottom and the track title layered on
 * top — YouTube-caption style — so it's legible even on bright covers.
 * If a song has no image (CSV-only playlists), we fall back to a plain
 * dark placeholder with the ♪ glyph.
 *
 * Layout:
 *   [ Unranked pool                              ]
 *   [ S | ...tiles...                            ]
 *   [ A | ...tiles...                            ]
 *   ...
 *
 * Tiles are draggable between any two rows. Clicking a tier label
 * (S/A/B/etc.) prompts a rename.
 */
public final class TierListView {

    /** Tile size in pixels — cover is square. */
    private static final double TILE_SIZE = 96;

    /**
     * Distinct drag format for reordering whole tier rows, so a
     * tier-row drag can never be mistaken for a song-tile drag (which
     * uses a plain string containing the song id) — the two live in
     * completely separate parts of the same row and need to be told
     * apart at both drag-start and drop time.
     */
    private static final DataFormat TIER_DRAG_FORMAT = new DataFormat("application/x-trackoff-tier-row");

    // ------------------------------------------------------------------
    //  Default tiers: (name, background color)
    // ------------------------------------------------------------------
    private static final String[][] DEFAULT_TIERS = {
            {"S", "#ff7f7f"},
            {"A", "#ffbf7f"},
            {"B", "#ffdf7f"},
            {"C", "#ffff7f"},
            {"D", "#bfff7f"},
            {"F", "#7fbfff"},
    };

    private final Stage    stage;
    private final Playlist playlist;
    private final List<Song> songs;

    /** Ordered rows; row 0 is always the unranked pool. */
    private final List<TierRow> rows = new ArrayList<>();

    /** Fast lookup during drag-and-drop. */
    private final Map<String, Song> songById = new HashMap<>();

    /** Stable identity for tier rows during a drag (names can be renamed/duplicated). */
    private int nextTierUid = 0;
    private final Map<Integer, TierRow> tierRowsByUid = new HashMap<>();

    /** Cache Images so we don't re-download when moving tiles between rows. */
    private final Map<String, Image> imageCache = new HashMap<>();

    /**
     * Live play buttons keyed by song id, refreshed on every buildTile()
     * call. Tiles are ephemeral — rebuilt on every drag-drop/auto-tier —
     * so playback state lives here at the view level instead, and this
     * map lets togglePlayback() find whichever button is currently on
     * screen for a given song (even one that wasn't just clicked, e.g.
     * to reset the previously-playing tile's icon back to ▶).
     */
    private final Map<String, Button> playButtons = new HashMap<>();
    private MediaPlayer currentPlayer;
    private String      currentPlayingSongId;

    private VBox tierColumn;

    public TierListView(Stage stage, Playlist playlist, List<Song> songs) {
        this.stage    = stage;
        this.playlist = playlist;
        this.songs    = new ArrayList<>(songs);
        for (Song s : this.songs) songById.put(s.id(), s);
    }

    // ==================================================================
    //  Scene assembly
    // ==================================================================
    public void show() {
        Label title = new Label("Tier List — " + playlist.name());
        title.getStyleClass().add("label-header");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button addTierBtn  = ghostButton("+ Add Tier");
        Button autoTierBtn = ghostButton("Auto-tier by rank");
        Button exportCsv   = primaryButton("Export as CSV");
        Button exportPng   = secondaryButton("Export as image");
        Button back        = ghostButton("Back");
        addTierBtn.setOnAction(e -> promptAddTier());
        autoTierBtn.setOnAction(e -> autoTierByRank());
        exportCsv .setOnAction(e -> exportCsv());
        exportPng .setOnAction(e -> exportPng());
        back      .setOnAction(e -> { disposeCurrentPlayer(); new MainView(stage).show(); });

        HBox headerRow = new HBox(10, title, headerSpacer, addTierBtn, autoTierBtn, exportCsv, exportPng, back);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        rows.clear();
        rows.add(new TierRow("Unranked", "#3a3a3a", true));
        for (String[] t : DEFAULT_TIERS) {
            rows.add(new TierRow(t[0], t[1], false));
        }
        // Everything starts unranked.
        for (Song s : songs) rows.get(0).addSong(s);

        tierColumn = new VBox(8);
        tierColumn.setFillWidth(true);
        for (TierRow r : rows) tierColumn.getChildren().add(r.node);

        ScrollPane scroll = new ScrollPane(tierColumn);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:transparent; -fx-background:transparent;");

        VBox root = new VBox(18, headerRow, scroll);
        root.setPadding(new Insets(24));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Theme.show(stage, root, 1180, 760);
        stage.setTitle("trackoff — Tier List");
        stage.setOnCloseRequest(e -> disposeCurrentPlayer());
    }

    // ==================================================================
    //  Tier row
    // ==================================================================
    private final class TierRow {

        final int uid;
        String  name;
        String  color;
        final boolean isPool;
        final FlowPane content;
        final Label   labelNode;
        final HBox    node;
        final List<Song> tierSongs = new ArrayList<>();

        TierRow(String name, String color, boolean isPool) {
            this.uid   = nextTierUid++;
            this.name  = name;
            this.color = color;
            this.isPool = isPool;
            tierRowsByUid.put(uid, this);

            content = new FlowPane();
            content.getStyleClass().add("tier-content");
            // Wrap based on the pane's actual width so the preferred *height*
            // is computed correctly (not "as if 1px wide" — which made rows huge).
            content.prefWrapLengthProperty().bind(content.widthProperty());
            HBox.setHgrow(content, Priority.ALWAYS);

            // Song-tile drops only — anything else (e.g. a tier-row reorder
            // drag) is deliberately left un-accepted/un-consumed here so it
            // bubbles up to the row-level (node) handlers below.
            content.setOnDragOver(e -> {
                if (e.getDragboard().hasString()) onDragOver(e);
            });
            content.setOnDragEntered(e -> {
                if (e.getDragboard().hasString()) content.getStyleClass().add("tier-content-target");
            });
            content.setOnDragExited(e -> content.getStyleClass().remove("tier-content-target"));
            content.setOnDragDropped(e -> {
                if (e.getDragboard().hasString()) onDragDropped(e, this);
            });

            // Tier-row reorder: dropping anywhere on this row's full width
            // (label or content) moves the dragged tier before/after it.
            HBox rowNode;

            if (isPool) {
                labelNode = new Label(name);
                labelNode.getStyleClass().add("label-section");
                labelNode.setMinWidth(70);
                labelNode.setAlignment(Pos.CENTER);
                content.getStyleClass().add("unranked-pool");
                rowNode = new HBox(10, labelNode, content);
            } else {
                labelNode = new Label(name);
                labelNode.getStyleClass().add("tier-label");
                labelNode.setStyle("-fx-background-color:" + color + ";");
                labelNode.setOnMouseClicked(e -> promptRename());

                Button deleteBtn = new Button("✕");
                deleteBtn.getStyleClass().add("tier-delete-btn");
                deleteBtn.setTooltip(new Tooltip("Delete this tier"));
                deleteBtn.setOnAction(e -> promptDeleteTier(this));

                VBox labelCol = new VBox(4, labelNode, deleteBtn);
                labelCol.setAlignment(Pos.CENTER);
                rowNode = new HBox(10, labelCol, content);
                rowNode.getStyleClass().add("tier-row");

                // Drag SOURCE — grabbing the label column reorders the whole
                // tier (dragging a song tile, inside `content`, is unaffected
                // since it's a sibling region with its own drag handling).
                labelCol.setOnDragDetected(e -> {
                    Dragboard db = labelCol.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent cc = new ClipboardContent();
                    cc.put(TIER_DRAG_FORMAT, String.valueOf(uid));
                    db.setContent(cc);
                    rowNode.getStyleClass().add("tier-row-dragging");
                    e.consume();
                });
                labelCol.setOnDragDone(e -> rowNode.getStyleClass().remove("tier-row-dragging"));
            }
            this.node = rowNode;
            node.setAlignment(Pos.CENTER_LEFT);

            // Drag TARGET — every row (including the pool, so a tier can be
            // dragged to become the very first one) accepts a tier-row drag.
            node.setOnDragOver(e -> {
                if (e.getDragboard().hasContent(TIER_DRAG_FORMAT)) {
                    e.acceptTransferModes(TransferMode.MOVE);
                }
                e.consume();
            });
            node.setOnDragEntered(e -> {
                if (e.getDragboard().hasContent(TIER_DRAG_FORMAT)) node.getStyleClass().add("tier-row-drop-target");
            });
            node.setOnDragExited(e -> node.getStyleClass().remove("tier-row-drop-target"));
            node.setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                boolean success = false;
                if (db.hasContent(TIER_DRAG_FORMAT)) {
                    int draggedUid = Integer.parseInt((String) db.getContent(TIER_DRAG_FORMAT));
                    TierRow dragged = tierRowsByUid.get(draggedUid);
                    if (dragged != null && dragged != this) {
                        reorderTier(dragged, this, e.getY());
                        success = true;
                    }
                }
                e.setDropCompleted(success);
                e.consume();
            });
        }

        void addSong(Song s) {
            addSong(s, tierSongs.size());
        }

        /** Insert {@code s} at position {@code idx} (clamped to the valid range). */
        void addSong(Song s, int idx) {
            if (idx < 0) idx = 0;
            if (idx > tierSongs.size()) idx = tierSongs.size();
            tierSongs.add(idx, s);
            content.getChildren().add(idx, buildTile(s));
        }

        void removeSong(Song s) {
            tierSongs.remove(s);
            content.getChildren().removeIf(n ->
                    s.id().equals(n.getProperties().get("songId")));
        }

        void promptRename() {
            TextInputDialog d = new TextInputDialog(name);
            d.setTitle("Rename tier");
            d.setHeaderText("New name for this tier:");
            d.setContentText("Name:");
            Theme.apply(d.getDialogPane().getScene());
            d.showAndWait().ifPresent(newName -> {
                String trimmed = newName.trim();
                if (!trimmed.isEmpty()) {
                    name = trimmed;
                    labelNode.setText(trimmed);
                }
            });
        }
    }

    // ==================================================================
    //  Tile construction — cover + gradient + title
    // ==================================================================
    private StackPane buildTile(Song s) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("song-tile");
        tile.setMinSize(TILE_SIZE, TILE_SIZE);
        tile.setPrefSize(TILE_SIZE, TILE_SIZE);
        tile.setMaxSize(TILE_SIZE, TILE_SIZE);
        tile.getProperties().put("songId", s.id());

        // ---- Layer 1: album cover (or placeholder) ----
        if (s.hasImage()) {
            ImageView iv = new ImageView(loadImage(s));
            iv.setFitWidth(TILE_SIZE);
            iv.setFitHeight(TILE_SIZE);
            iv.setPreserveRatio(false);   // fill the square edge-to-edge
            iv.setSmooth(true);

            // Clip to rounded corners.
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(TILE_SIZE, TILE_SIZE);
            clip.setArcWidth(12);
            clip.setArcHeight(12);
            iv.setClip(clip);
            tile.getChildren().add(iv);
        } else {
            StackPane placeholder = new StackPane();
            placeholder.getStyleClass().add("song-tile-placeholder");
            placeholder.setPrefSize(TILE_SIZE, TILE_SIZE);
            Label glyph = new Label("♪");
            glyph.getStyleClass().add("song-tile-placeholder-label");
            placeholder.getChildren().add(glyph);
            tile.getChildren().add(placeholder);
        }

        // ---- Layer 2: dark gradient overlay across the bottom half ----
        Region gradient = new Region();
        gradient.getStyleClass().add("song-tile-gradient");
        gradient.setPrefSize(TILE_SIZE, TILE_SIZE);
        gradient.setMouseTransparent(true);
        tile.getChildren().add(gradient);

        // ---- Layer 3: title label pinned to bottom ----
        Label title = new Label(s.title());
        title.getStyleClass().add("song-tile-title");
        title.setWrapText(true);
        title.setMaxWidth(TILE_SIZE - 8);
        title.setAlignment(Pos.BOTTOM_CENTER);
        title.setMouseTransparent(true);
        StackPane.setAlignment(title, Pos.BOTTOM_CENTER);
        StackPane.setMargin(title, new Insets(0, 4, 4, 4));
        tile.getChildren().add(title);

        // ---- Layer 4: preview play button, top-right corner ----
        Button playBtn = new Button(s.id().equals(currentPlayingSongId) ? "⏸" : "▶");
        playBtn.getStyleClass().add("tile-play-overlay");
        playBtn.setOnAction(e -> togglePlayback(s));
        StackPane.setAlignment(playBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(playBtn, new Insets(4, 4, 0, 0));
        tile.getChildren().add(playBtn);
        playButtons.put(s.id(), playBtn);

        // ---- Tooltip so the full title + artist is available on hover ----
        Tooltip tip = new Tooltip(s.title() + "\n" + s.artist()
                + (s.album().isEmpty() ? "" : "\n" + s.album()));
        Tooltip.install(tile, tip);

        // ---- Drag support ----
        tile.setOnDragDetected(e -> {
            Dragboard db = tile.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(s.id());
            db.setContent(cc);
            tile.getStyleClass().add("song-tile-dragging");
            e.consume();
        });
        tile.setOnDragDone(e -> tile.getStyleClass().remove("song-tile-dragging"));

        return tile;
    }

    /** Lazily load and cache the album cover for a song. */
    private Image loadImage(Song s) {
        return imageCache.computeIfAbsent(s.id(), k -> {
            try {
                // backgroundLoading = true so the UI doesn't freeze while downloading
                return new Image(s.imageUrl(),
                        TILE_SIZE, TILE_SIZE,
                        false, true, true);
            } catch (Exception ex) {
                return null;
            }
        });
    }

    // ==================================================================
    //  Preview playback — lazy: resolved on first click of a tile's play
    //  button, same Deezer-backed lookup as ComparisonView/SwipeView/
    //  LastFmManagerView. Only one preview plays at a time; state lives
    //  here at the view level (not on the tile) since tiles get rebuilt
    //  on every drag-drop or auto-tier.
    // ==================================================================

    private void togglePlayback(Song s) {
        if (s.id().equals(currentPlayingSongId) && currentPlayer != null) {
            if (currentPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
                currentPlayer.pause();
                setPlayButtonText(s.id(), "▶");
            } else {
                currentPlayer.play();
                setPlayButtonText(s.id(), "⏸");
            }
            return;
        }

        disposeCurrentPlayer();

        Button btn = playButtons.get(s.id());
        if (btn != null) btn.setDisable(true);
        PreviewLookup.resolveAsync(s, previewUrl -> {
            if (btn != null) btn.setDisable(false);
            if (previewUrl.isEmpty()) return;   // no preview found; button stays inert

            try {
                Media media = new Media(previewUrl.get());
                MediaPlayer player = new MediaPlayer(media);
                player.setVolume(0.7);
                player.setOnEndOfMedia(() -> {
                    player.stop();
                    setPlayButtonText(s.id(), "▶");
                    if (s.id().equals(currentPlayingSongId)) currentPlayingSongId = null;
                });
                currentPlayer = player;
                currentPlayingSongId = s.id();
                player.play();
                setPlayButtonText(s.id(), "⏸");
            } catch (Exception ignored) {
                // Best-effort, same as elsewhere: a bad media URL just means no playback.
            }
        });
    }

    private void setPlayButtonText(String songId, String text) {
        Button b = playButtons.get(songId);
        if (b != null) b.setText(text);
    }

    private void disposeCurrentPlayer() {
        if (currentPlayer != null) {
            currentPlayer.dispose();
            if (currentPlayingSongId != null) setPlayButtonText(currentPlayingSongId, "▶");
        }
        currentPlayer = null;
        currentPlayingSongId = null;
    }

    // ==================================================================
    //  Drag & drop
    // ==================================================================
    private void onDragOver(DragEvent e) {
        if (e.getDragboard().hasString()) e.acceptTransferModes(TransferMode.MOVE);
        e.consume();
    }

    private void onDragDropped(DragEvent e, TierRow target) {
        String id = e.getDragboard().getString();
        Song   s  = songById.get(id);
        boolean success = false;

        if (s != null) {
            // Figure out where in the target row the pointer landed *before* we
            // remove the song, so index math is done against the current layout.
            int insertAt = computeDropIndex(target.content, e.getX(), e.getY());

            // If the song is being reordered inside the same row and its old
            // position was before the drop point, removing it shifts everything
            // after it left by one — compensate so the tile lands where the user
            // actually pointed instead of one slot to the right.
            if (target.tierSongs.contains(s)) {
                int oldIndex = target.tierSongs.indexOf(s);
                if (oldIndex < insertAt) insertAt--;
            }

            for (TierRow r : rows) if (r.tierSongs.contains(s)) r.removeSong(s);
            target.addSong(s, insertAt);
            success = true;
        }
        e.setDropCompleted(success);
        e.consume();
    }
    /**
     * Given a pointer position inside {@code pane}, figure out which child
     * index a dropped tile should occupy. Walks the current tiles, finds the
     * row (band of similar layoutY) closest to the pointer, then within that
     * row picks the first tile whose horizontal center is past the pointer.
     * Returns {@code children.size()} when the pointer is past every tile
     * (i.e. dropping at the end).
     */
    private int computeDropIndex(FlowPane pane, double x, double y) {
        var kids = pane.getChildren();
        if (kids.isEmpty()) return 0;

        // 1. Find which row (by Y band) the pointer is closest to.
        double bestRowY = kids.get(0).getBoundsInParent().getMinY();
        double bestDist = Double.MAX_VALUE;
        for (var n : kids) {
            var b = n.getBoundsInParent();
            double centerY = b.getMinY() + b.getHeight() / 2.0;
            double d = Math.abs(y - centerY);
            if (d < bestDist) {
                bestDist = d;
                bestRowY = b.getMinY();
            }
        }

        // 2. Within that row, first tile whose horizontal center is past x.
        for (int i = 0; i < kids.size(); i++) {
            var b = kids.get(i).getBoundsInParent();
            boolean sameRow = Math.abs(b.getMinY() - bestRowY) < 4;   // small slack
            if (!sameRow) continue;
            double centerX = b.getMinX() + b.getWidth() / 2.0;
            if (x < centerX) return i;
        }

        // 3. Past every tile in the last row â†’ append.
        return kids.size();
    }
    // ==================================================================
    //  Actions
    // ==================================================================
    private void autoTierByRank() {
        List<Song> all = new ArrayList<>(songs);
        int total = all.size();
        if (total == 0) return;

        // Excludes the pool (row 0) — tier count is user-controlled now
        // (Add/Delete Tier), so this can't assume a fixed 6-tier layout.
        List<TierRow> tierRows = rows.subList(1, rows.size());
        int numTiers = tierRows.size();
        if (numTiers == 0) return;

        for (TierRow r : rows) {
            r.tierSongs.clear();
            r.content.getChildren().clear();
        }

        int idx = 0;
        int base = total / numTiers;
        int remainder = total % numTiers;
        for (int t = 0; t < numTiers && idx < total; t++) {
            int count = base + (t < remainder ? 1 : 0);
            for (int k = 0; k < count && idx < total; k++, idx++) {
                tierRows.get(t).addSong(all.get(idx));
            }
        }
    }

    // ==================================================================
    //  Tier create / delete
    // ==================================================================
    private void promptAddTier() {
        TextInputDialog d = new TextInputDialog("New Tier");
        d.setTitle("Add tier");
        d.setHeaderText("Name for the new tier:");
        d.setContentText("Name:");
        Theme.apply(d.getDialogPane().getScene());
        d.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) return;
            String color = DEFAULT_TIERS[(rows.size() - 1) % DEFAULT_TIERS.length][1];
            TierRow row = new TierRow(trimmed, color, false);
            rows.add(row);
            tierColumn.getChildren().add(row.node);
        });
    }

    private void promptDeleteTier(TierRow row) {
        if (row.isPool) return;   // safety net — no delete button is ever placed on the pool

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete tier");
        confirm.setHeaderText("Delete tier \"" + row.name + "\"?");
        confirm.setContentText(row.tierSongs.size() + " song(s) in this tier will move back to Unranked.");
        Theme.apply(confirm.getDialogPane().getScene());
        confirm.showAndWait()
                .filter(bt -> bt == ButtonType.OK)
                .ifPresent(bt -> deleteTier(row));
    }

    private void deleteTier(TierRow row) {
        for (Song s : new ArrayList<>(row.tierSongs)) {
            row.removeSong(s);
            rows.get(0).addSong(s);
        }
        rows.remove(row);
        tierColumn.getChildren().remove(row.node);
    }

    /**
     * Move {@code dragged} to just before or after {@code target}, based on
     * which half of the target row {@code dropY} landed in. The pool (row 0)
     * can never move and can never be displaced from index 0 — dropping a
     * tier on the pool row inserts it as the new first tier instead.
     */
    private void reorderTier(TierRow dragged, TierRow target, double dropY) {
        rows.remove(dragged);
        int targetIdx = rows.indexOf(target);
        boolean insertAfter = dropY > target.node.getHeight() / 2.0;
        int insertIdx = insertAfter ? targetIdx + 1 : targetIdx;
        insertIdx = Math.max(insertIdx, 1);   // never before the pool
        rows.add(insertIdx, dragged);
        tierColumn.getChildren().setAll(rows.stream().map(r -> r.node).toList());
    }

    private void exportCsv() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export tier list");
        fc.setInitialFileName(playlist.name() + " (tiers).csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(file.getAbsolutePath())))) {
            out.println("Tier,Position,Song,Artist,Album,Spotify Track Id");
            for (TierRow r : rows) {
                for (int i = 0; i < r.tierSongs.size(); i++) {
                    Song s = r.tierSongs.get(i);
                    out.println(String.join(",",
                            csv(r.name),
                            String.valueOf(i + 1),
                            csv(s.title()),
                            csv(s.artist()),
                            csv(s.album()),
                            csv(s.id())));
                }
            }
            Alert a = new Alert(Alert.AlertType.INFORMATION, "Exported to " + file.getName());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        } catch (Exception ex) {
            Alert a = new Alert(Alert.AlertType.ERROR, "Export failed: " + ex.getMessage());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        }
    }

    private void exportPng() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export tier list image");
        fc.setInitialFileName(playlist.name() + " (tiers).png");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;

        try {
            WritableImage img = tierColumn.snapshot(null, null);
            BufferedImage bimg = new BufferedImage(
                    (int) img.getWidth(), (int) img.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    bimg.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                }
            }
            ImageIO.write(bimg, "png", file);

            Alert a = new Alert(Alert.AlertType.INFORMATION, "Saved to " + file.getName());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        } catch (Exception ex) {
            Alert a = new Alert(Alert.AlertType.ERROR, "Image export failed: " + ex.getMessage());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        }
    }

    private String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    // ==================================================================
    //  Button helpers
    // ==================================================================
    private Button primaryButton(String t)   { Button b = new Button(t); b.getStyleClass().add("button-primary");   return b; }
    private Button secondaryButton(String t) { Button b = new Button(t); b.getStyleClass().add("button-secondary"); return b; }
    private Button ghostButton(String t)     { Button b = new Button(t); b.getStyleClass().add("button-ghost");     return b; }
}
