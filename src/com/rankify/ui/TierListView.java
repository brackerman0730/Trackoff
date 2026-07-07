package com.rankify.ui;

import com.rankify.model.Playlist;
import com.rankify.model.Song;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

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

    /** Cache Images so we don't re-download when moving tiles between rows. */
    private final Map<String, Image> imageCache = new HashMap<>();

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

        Button autoTierBtn = ghostButton("Auto-tier by rank");
        Button exportCsv   = primaryButton("Export as CSV");
        Button exportPng   = secondaryButton("Export as image");
        Button back        = ghostButton("Back");
        autoTierBtn.setOnAction(e -> autoTierByRank());
        exportCsv .setOnAction(e -> exportCsv());
        exportPng .setOnAction(e -> exportPng());
        back      .setOnAction(e -> new MainView(stage).show());

        HBox headerRow = new HBox(10, title, headerSpacer, autoTierBtn, exportCsv, exportPng, back);
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

        Scene scene = new Scene(root, 1180, 760);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Rankify — Tier List");
    }

    // ==================================================================
    //  Tier row
    // ==================================================================
    private final class TierRow {

        String  name;
        String  color;
        final boolean isPool;
        final FlowPane content;
        final Label   labelNode;
        final HBox    node;
        final List<Song> tierSongs = new ArrayList<>();

        TierRow(String name, String color, boolean isPool) {
            this.name  = name;
            this.color = color;
            this.isPool = isPool;

            content = new FlowPane();
            content.getStyleClass().add("tier-content");
            // Wrap based on the pane's actual width so the preferred *height*
            // is computed correctly (not "as if 1px wide" — which made rows huge).
            content.prefWrapLengthProperty().bind(content.widthProperty());
            HBox.setHgrow(content, Priority.ALWAYS);

            content.setOnDragOver   (e -> onDragOver(e));
            content.setOnDragEntered(e -> content.getStyleClass().add("tier-content-target"));
            content.setOnDragExited (e -> content.getStyleClass().remove("tier-content-target"));
            content.setOnDragDropped(e -> onDragDropped(e, this));

            if (isPool) {
                labelNode = new Label(name);
                labelNode.getStyleClass().add("label-section");
                labelNode.setMinWidth(70);
                labelNode.setAlignment(Pos.CENTER);
                content.getStyleClass().add("unranked-pool");
                node = new HBox(10, labelNode, content);
            } else {
                labelNode = new Label(name);
                labelNode.getStyleClass().add("tier-label");
                labelNode.setStyle("-fx-background-color:" + color + ";");
                labelNode.setOnMouseClicked(e -> promptRename());
                node = new HBox(10, labelNode, content);
                node.getStyleClass().add("tier-row");
            }
            node.setAlignment(Pos.CENTER_LEFT);
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

        // 3. Past every tile in the last row → append.
        return kids.size();
    }
    // ==================================================================
    //  Actions
    // ==================================================================
    private void autoTierByRank() {
        List<Song> all = new ArrayList<>(songs);
        int total = all.size();
        if (total == 0) return;

        for (TierRow r : rows) {
            r.tierSongs.clear();
            r.content.getChildren().clear();
        }

        double[] pct = {0.10, 0.20, 0.25, 0.25, 0.15, 0.05};
        int idx = 0;
        for (int t = 0; t < 6 && idx < total; t++) {
            int count = (int) Math.round(pct[t] * total);
            if (t == 5) count = total - idx;
            for (int k = 0; k < count && idx < total; k++, idx++) {
                rows.get(t + 1).addSong(all.get(idx));
            }
        }
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