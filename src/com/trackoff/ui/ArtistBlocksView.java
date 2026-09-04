package com.trackoff.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Groups a playlist into one block per artist, sortable by the three
 * numbers that actually distinguish artists from each other.
 *
 * The two play-count columns are deliberately different measurements
 * and the gap between them is the interesting part:
 *   - <b>plays here</b> — the sum of this artist's tracks' play counts,
 *     limited to the tracks in THIS playlist.
 *   - <b>all-time</b> — the account's total scrobbles for the artist
 *     across the whole library ({@code artist.getinfo}).
 * An artist with 4,000 all-time plays but 30 in this playlist is
 * represented here by tracks you don't actually reach for; the reverse
 * means this playlist is where you listen to them.
 *
 * Blocks collapse to a single row and expand on click; the track list
 * inside is built lazily on first expand, because a large playlist can
 * be several hundred artists and building every track row up front is
 * work for rows that mostly never get looked at.
 */
public final class ArtistBlocksView extends VBox {

    /** One track of an artist's block. {@code plays} is meaningless when {@code hasPlays} is false. */
    public record TrackEntry(int position, String title, long plays, boolean hasPlays) {}

    /**
     * One artist's group.
     *
     * @param playlistPlays    summed play counts of {@link #tracks} that have data
     * @param tracksWithPlays  how many tracks contributed to that sum
     * @param artistPlays      whole-library play count for the artist
     * @param hasArtistPlays   false when the artist count was never fetched
     */
    public record ArtistBlock(
            String displayName,
            List<TrackEntry> tracks,
            long playlistPlays,
            int tracksWithPlays,
            long artistPlays,
            boolean hasArtistPlays
    ) {
        public int trackCount() { return tracks.size(); }
    }

    /** How the blocks are ordered, and which number the bar shows. */
    public enum Sort {
        TRACKS       ("Tracks",      "tracks in this playlist"),
        PLAYLIST_PLAYS("Plays here", "plays on this artist's tracks in this playlist"),
        ARTIST_PLAYS ("All-time",    "your total plays for this artist, whole library"),
        NAME         ("A–Z",         "artist name");

        private final String label, tooltip;
        Sort(String label, String tooltip) { this.label = label; this.tooltip = tooltip; }
    }

    private static final double BAR_WIDTH  = 160;
    private static final double BAR_HEIGHT = 8;

    private final VBox       blocksBox = new VBox(4);
    private final ScrollPane scroll    = new ScrollPane(blocksBox);
    private final Label      summary   = new Label();
    private final List<Button> sortButtons = new ArrayList<>();

    private List<ArtistBlock> blocks = List.of();
    private Sort sort = Sort.TRACKS;

    public ArtistBlocksView() {
        super(8);

        summary.getStyleClass().add("label-song-meta");

        HBox sortRow = new HBox(6);
        sortRow.setAlignment(Pos.CENTER_LEFT);
        Label sortLabel = new Label("Sort by");
        sortLabel.getStyleClass().add("label-song-meta");
        sortRow.getChildren().add(sortLabel);
        for (Sort s : Sort.values()) {
            Button b = new Button(s.label);
            b.getStyleClass().add("graph-zoom-btn");
            b.setTooltip(new javafx.scene.control.Tooltip(s.tooltip));
            b.setOnAction(e -> { sort = s; markActiveSort(); render(); });
            sortButtons.add(b);
            sortRow.getChildren().add(b);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        sortRow.getChildren().addAll(spacer, summary);

        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:transparent; -fx-background:transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        markActiveSort();
        getChildren().addAll(sortRow, scroll);
        setPadding(new Insets(4, 0, 0, 0));
    }

    private void markActiveSort() {
        for (int i = 0; i < sortButtons.size(); i++) {
            Button b = sortButtons.get(i);
            b.getStyleClass().remove("graph-zoom-btn-active");
            if (Sort.values()[i] == sort) b.getStyleClass().add("graph-zoom-btn-active");
        }
    }

    public void setData(List<ArtistBlock> blocks) {
        this.blocks = List.copyOf(blocks);
        updateSummary();
        render();
    }

    private void updateSummary() {
        if (blocks.isEmpty()) { summary.setText(""); return; }
        long missingArtistPlays = blocks.stream().filter(b -> !b.hasArtistPlays()).count();
        int tracks = blocks.stream().mapToInt(ArtistBlock::trackCount).sum();
        String text = String.format("%d artist%s  ·  %d track%s",
                blocks.size(), blocks.size() == 1 ? "" : "s",
                tracks, tracks == 1 ? "" : "s");
        if (missingArtistPlays > 0) {
            text += String.format("  ·  %d artist%s without all-time plays",
                    missingArtistPlays, missingArtistPlays == 1 ? "" : "s");
        }
        summary.setText(text);
    }

    // ==================================================================
    //  Rendering
    // ==================================================================

    private void render() {
        List<ArtistBlock> ordered = new ArrayList<>(blocks);
        ordered.sort(comparator());

        // The bar always measures whatever the list is sorted by, so the
        // ordering is legible as a shape and not just as a sequence.
        long max = ordered.stream().mapToLong(this::metric).max().orElse(0);

        List<Node> nodes = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            nodes.add(buildBlock(ordered.get(i), i + 1, max));
        }
        blocksBox.getChildren().setAll(nodes);
        scroll.setVvalue(0);
    }

    private Comparator<ArtistBlock> comparator() {
        return switch (sort) {
            // Ties broken by name so the order is stable and predictable
            // rather than dependent on playlist order.
            case TRACKS -> Comparator.comparingInt(ArtistBlock::trackCount).reversed()
                    .thenComparing(b -> b.displayName().toLowerCase());
            case PLAYLIST_PLAYS -> Comparator.comparingLong(ArtistBlock::playlistPlays).reversed()
                    .thenComparing(b -> b.displayName().toLowerCase());
            // Never-fetched artists sort last rather than as zero: absent
            // data isn't the same claim as "you've never played them".
            case ARTIST_PLAYS -> Comparator.<ArtistBlock, Boolean>comparing(b -> !b.hasArtistPlays())
                    .thenComparing(Comparator.comparingLong(ArtistBlock::artistPlays).reversed())
                    .thenComparing(b -> b.displayName().toLowerCase());
            case NAME -> Comparator.comparing(b -> b.displayName().toLowerCase());
        };
    }

    private long metric(ArtistBlock b) {
        return switch (sort) {
            case TRACKS         -> b.trackCount();
            case PLAYLIST_PLAYS -> b.playlistPlays();
            case ARTIST_PLAYS   -> b.hasArtistPlays() ? b.artistPlays() : 0;
            case NAME           -> b.trackCount();
        };
    }

    private Node buildBlock(ArtistBlock block, int rank, long max) {
        Label rankLabel = new Label(String.valueOf(rank));
        rankLabel.getStyleClass().add("label-song-meta");
        rankLabel.setMinWidth(32);

        Label caret = new Label("▸");
        caret.getStyleClass().add("label-song-meta");
        caret.setMinWidth(12);

        Label name = new Label(block.displayName());
        name.getStyleClass().add("label-song-title");
        name.setStyle("-fx-font-size: 15px;");

        VBox nameBox = new VBox(2, name, metricsLabel(block));
        nameBox.setMinWidth(240);
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        HBox header = new HBox(12, rankLabel, caret, nameBox, bar(block, max));
        header.getStyleClass().add("lastfm-manager-row");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 8, 12));

        VBox container = new VBox(2, header);
        VBox tracksBox = new VBox(1);
        tracksBox.setPadding(new Insets(2, 12, 8, 58));

        header.setOnMouseClicked(e -> {
            boolean expanding = !container.getChildren().contains(tracksBox);
            if (expanding) {
                if (tracksBox.getChildren().isEmpty()) {
                    for (TrackEntry t : block.tracks()) tracksBox.getChildren().add(trackRow(t));
                }
                container.getChildren().add(tracksBox);
                caret.setText("▾");
            } else {
                container.getChildren().remove(tracksBox);
                caret.setText("▸");
            }
        });

        return container;
    }

    private Label metricsLabel(ArtistBlock block) {
        String tracks = block.trackCount() + (block.trackCount() == 1 ? " track" : " tracks");

        String here = block.tracksWithPlays() == 0
                ? "no play data"
                : String.format("%,d plays here", block.playlistPlays());
        if (block.tracksWithPlays() > 0 && block.tracksWithPlays() < block.trackCount()) {
            // Be explicit that the sum is partial rather than quietly
            // under-reporting an artist against fully-fetched ones.
            here += String.format(" (%d of %d tracks)", block.tracksWithPlays(), block.trackCount());
        }

        String allTime = block.hasArtistPlays()
                ? String.format("%,d all-time", block.artistPlays())
                : "all-time not fetched";

        Label l = new Label(tracks + "  ·  " + here + "  ·  " + allTime);
        l.getStyleClass().add("label-song-meta");
        return l;
    }

    private Node bar(ArtistBlock block, long max) {
        Region track = new Region();
        track.getStyleClass().add("playcount-bar-track");
        track.setMinSize(BAR_WIDTH, BAR_HEIGHT);
        track.setPrefSize(BAR_WIDTH, BAR_HEIGHT);
        track.setMaxSize(BAR_WIDTH, BAR_HEIGHT);

        Region fill = new Region();
        fill.getStyleClass().add("playcount-bar-fill");
        double value = metric(block);
        double width = (max <= 0 || value <= 0) ? 0 : Math.max(4, value / (double) max * BAR_WIDTH);
        fill.setMinSize(width, BAR_HEIGHT);
        fill.setPrefSize(width, BAR_HEIGHT);
        // Plain Regions default to an unbounded max width, so StackPane
        // would stretch them across the whole track.
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        fill.setMaxHeight(BAR_HEIGHT);

        StackPane stack = new StackPane(track, fill);
        stack.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        stack.setMinWidth(BAR_WIDTH);
        return stack;
    }

    private Node trackRow(TrackEntry t) {
        Label pos = new Label("#" + t.position());
        pos.getStyleClass().add("label-song-meta");
        pos.setMinWidth(46);

        Label title = new Label(t.title());
        title.getStyleClass().add("label-song-artist");
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);

        Label plays = new Label(t.hasPlays() ? String.format("%,d plays", t.plays()) : "—");
        plays.getStyleClass().add("label-song-meta");
        plays.setMinWidth(80);

        HBox row = new HBox(10, pos, title, plays);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 4, 2, 4));
        return row;
    }
}
