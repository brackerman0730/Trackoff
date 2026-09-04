package com.trackoff.ui;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Bar-graph view of a playlist's Last.fm play counts, for answering
 * "where in this playlist are all my plays?" at a glance.
 *
 * Y axis is position in the playlist (song 1 at the top, running down
 * in playlist order); X axis is play count, so each song's bar grows
 * rightward. Reading top-to-bottom shows the shape of the playlist —
 * front-loaded, back-loaded, or a few spikes in the middle.
 *
 * <h3>Virtualized drawing</h3>
 * The canvas is always exactly viewport-sized and only the rows
 * currently in view are painted; scrolling is driven by an explicit
 * {@link ScrollBar} that moves {@link #scrollY}. The obvious
 * alternative — a content-sized canvas inside a ScrollPane — hard-locks
 * the app: a 1400-song playlist zoomed in wants a canvas some 12000px
 * tall, Prism can't allocate a render texture that big, and the render
 * thread then throws {@code NPE ... RTTexture.createGraphics()} on
 * every pulse. The UI thread stays alive but nothing ever repaints
 * again, so the window looks frozen and has to be killed. Virtualizing
 * caps texture size at the window size no matter how far the user
 * zooms, and makes repaints O(visible rows) instead of O(playlist).
 *
 * <h3>Two scales, because there are two questions</h3>
 * "Fit" puts the whole playlist on one screen to show the distribution;
 * zooming in makes individual songs readable and hoverable. Fit can't
 * always give every song its own pixel row (1400 songs into ~500px), so
 * past that density songs are BINNED: each drawn row covers a run of
 * adjacent songs and plots the highest play count in that run. Binning
 * rather than sub-pixel bars matters because sub-pixel bars alpha-blend
 * into an even smear — precisely destroying the spikes the graph exists
 * to show. Taking the max keeps every peak visible; hovering a binned
 * row names the song responsible for it.
 */
public final class PlaycountGraph extends VBox {

    /** One song's entry. {@code plays} is meaningless when {@code hasData} is false. */
    public record Bar(int position, String title, String artist, long plays, boolean hasData) {}

    /**
     * One painted row: the songs {@code [from, to)} it covers and the
     * index of the highest-played song among them (its representative).
     * {@code from + 1 == to} in the unbinned case.
     */
    private record RenderRow(int from, int to, int rep) {
        boolean binned() { return to - from > 1; }
    }

    // ----- Geometry -----
    private static final double LEFT_GUTTER = 54;   // song-number labels
    private static final double RIGHT_PAD   = 20;
    private static final double TOP_PAD     = 10;
    private static final double BOTTOM_PAD  = 14;
    private static final double AXIS_HEIGHT = 36;   // pinned scale strip above the plot

    /** A painted row thinner than this is invisible; below it we bin instead of shrinking. */
    private static final double MIN_ROW_H = 2.0;
    private static final double MAX_ROW_H = 34;

    // ----- Palette (matches styles.css) -----
    private static final Color BAR         = Color.web("#1db954");
    private static final Color BAR_TOP     = Color.web("#1ed760");
    private static final Color BAR_HOVER   = Color.web("#ffffff");
    private static final Color NO_DATA     = Color.web("#5a5a5a");
    private static final Color GRID        = Color.web("#2e2e2e");
    private static final Color GRID_STRONG = Color.web("#4a4a4a");
    private static final Color TEXT_MUTED  = Color.web("#a0a0a0");
    private static final Color TEXT_DIM    = Color.web("#6f6f6f");
    private static final Color ROW_HILITE  = Color.web("#ffffff", 0.07);
    private static final Color LABEL_ON_BAR = Color.web("#0b2f18");

    private static final Font LABEL_FONT      = Font.font("Segoe UI", 10.5);
    private static final Font LABEL_FONT_BOLD = Font.font("Segoe UI", FontWeight.BOLD, 10.5);

    private final Canvas    axisCanvas = new Canvas(100, AXIS_HEIGHT);
    private final Canvas    bodyCanvas = new Canvas(100, 100);
    private final Pane      plotArea   = new Pane(bodyCanvas);
    private final ScrollBar vbar       = new ScrollBar();
    private final Label     readout    = new Label();
    private final Label     summary    = new Label();

    private List<Bar> bars = List.of();
    private List<RenderRow> renderRows = List.of();

    private long   niceMax   = 1;
    private long   actualMax = 0;
    private double rowH      = 12;
    /** null = fit the whole playlist on screen; otherwise an explicit row height in px. */
    private Double zoomRowH  = null;
    private int    hoverRow  = -1;
    /** Pixels scrolled down from the top of the (virtual) plot. */
    private double scrollY   = 0;

    public PlaycountGraph() {
        super(8);

        readout.getStyleClass().add("label-song-meta");
        readout.setMinHeight(16);
        summary.getStyleClass().add("label-song-meta");

        Button zoomOut = zoomButton("−", "Zoom out — more songs per screen");
        Button zoomIn  = zoomButton("+", "Zoom in — taller bars, song titles, one row per song");
        Button fit     = zoomButton("Fit", "Fit the whole playlist on one screen");
        zoomOut.setOnAction(e -> nudgeZoom(1 / 1.6));
        zoomIn .setOnAction(e -> nudgeZoom(1.6));
        fit    .setOnAction(e -> zoomToFit());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(6, summary, spacer, zoomOut, fit, zoomIn);
        controls.setAlignment(Pos.CENTER_LEFT);

        vbar.setOrientation(Orientation.VERTICAL);
        vbar.setMin(0);
        vbar.valueProperty().addListener((o, a, b) -> {
            scrollY = b.doubleValue();
            drawBody();
        });

        HBox.setHgrow(plotArea, Priority.ALWAYS);
        HBox plotRow = new HBox(plotArea, vbar);
        VBox.setVgrow(plotRow, Priority.ALWAYS);

        // The canvas fills whatever the layout gives the plot area; only
        // ever the viewport's worth of pixels, never the content's.
        plotArea.widthProperty().addListener((o, a, b) -> relayout());
        plotArea.heightProperty().addListener((o, a, b) -> relayout());

        plotArea.setOnScroll(e -> scrollBy(-e.getDeltaY()));
        bodyCanvas.setOnMouseMoved(e -> {
            int r = rowAt(e.getY());
            if (r != hoverRow) { hoverRow = r; drawBody(); updateReadout(); }
        });
        bodyCanvas.setOnMouseExited(e -> {
            if (hoverRow != -1) { hoverRow = -1; drawBody(); updateReadout(); }
        });

        getChildren().addAll(controls, axisCanvas, plotRow, readout);
        setPadding(new Insets(4, 0, 0, 0));
    }

    private Button zoomButton(String text, String tip) {
        Button b = new Button(text);
        b.getStyleClass().add("graph-zoom-btn");
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    // ==================================================================
    //  Data
    // ==================================================================

    /**
     * Replace the graph's contents. Called when the view opens and again
     * whenever an update run lands new play counts, so it has to cope
     * with a partially-populated playlist: songs whose counts were never
     * fetched draw as a faint stub, not as zero. Never-fetched and
     * confirmed-zero are different facts and the graph shouldn't blur
     * them together.
     */
    public void setData(List<Bar> bars) {
        this.bars = List.copyOf(bars);
        this.actualMax = this.bars.stream().filter(Bar::hasData).mapToLong(Bar::plays).max().orElse(0);
        this.niceMax = niceCeiling(actualMax);
        this.hoverRow = -1;
        updateSummary();
        updateReadout();
        relayout();
    }

    private void updateSummary() {
        long withData = bars.stream().filter(Bar::hasData).count();
        if (withData == 0) {
            summary.setText("No play counts yet — press \"Update Last.fm plays\".");
            return;
        }

        long total = bars.stream().filter(Bar::hasData).mapToLong(Bar::plays).sum();

        // Concentration: what share of all plays sits in the top tenth of
        // the songs. This is the number the graph is really read for —
        // "are my plays spread out, or piled into a handful of tracks?"
        List<Long> sorted = new ArrayList<>(bars.stream().filter(Bar::hasData).map(Bar::plays).toList());
        sorted.sort((a, b) -> Long.compare(b, a));
        int topN = Math.max(1, (int) Math.round(sorted.size() * 0.10));
        long topSum = sorted.subList(0, topN).stream().mapToLong(Long::longValue).sum();
        int pct = total == 0 ? 0 : (int) Math.round(topSum * 100.0 / total);

        String text = String.format("%,d plays across %d songs  ·  top %d songs = %d%% of all plays",
                total, withData, topN, pct);
        if (withData < bars.size()) {
            text += String.format("  ·  %d not fetched", bars.size() - withData);
        }
        summary.setText(text);
    }

    private void updateReadout() {
        if (bars.isEmpty()) { readout.setText(""); return; }
        if (hoverRow < 0 || hoverRow >= renderRows.size()) {
            readout.setText(binned()
                    ? "Hover for the top song in each band  ·  \"+\" to zoom in to one row per song"
                    : "Hover a bar for song details");
            return;
        }

        RenderRow r = renderRows.get(hoverRow);
        Bar b = bars.get(r.rep());
        String plays = b.hasData() ? String.format("%,d plays", b.plays()) : "no play data";

        if (r.binned()) {
            readout.setText(String.format("songs %d–%d  ·  top: %s — %s  ·  %s",
                    bars.get(r.from()).position(), bars.get(r.to() - 1).position(),
                    b.title(), b.artist(), plays));
        } else {
            readout.setText(String.format("#%d  %s — %s  ·  %s",
                    b.position(), b.title(), b.artist(), plays));
        }
    }

    private boolean binned() {
        return !renderRows.isEmpty() && renderRows.size() < bars.size();
    }

    // ==================================================================
    //  Layout / zoom / scrolling
    // ==================================================================

    private void zoomToFit() {
        zoomRowH = null;
        relayout();
    }

    private void nudgeZoom(double factor) {
        // Anchor on the song at the top of the view, not the row index:
        // binning changes how many rows a song sits behind, so a row
        // index means something different before and after the zoom.
        int anchorSong = topVisibleSong();

        double target = rowH * factor;
        // Zooming out past the floor means the user wants everything on
        // screen, which is what Fit does (and Fit can go denser than an
        // explicit row height can, via binning).
        zoomRowH = target <= MIN_ROW_H ? null : clampRowH(target);

        relayout();
        scrollToSong(anchorSong);
    }

    private double clampRowH(double h) {
        return Math.max(MIN_ROW_H, Math.min(MAX_ROW_H, h));
    }

    /** Index into {@link #bars} of the first song currently visible. */
    private int topVisibleSong() {
        if (renderRows.isEmpty() || rowH <= 0) return 0;
        int row = Math.max(0, Math.min(renderRows.size() - 1, (int) Math.floor(scrollY / rowH)));
        return renderRows.get(row).from();
    }

    private void scrollToSong(int songIndex) {
        for (int r = 0; r < renderRows.size(); r++) {
            if (renderRows.get(r).to() > songIndex) {
                setScroll(r * rowH);
                return;
            }
        }
        setScroll(0);
    }

    private void scrollBy(double dy) { setScroll(scrollY + dy); }

    private void setScroll(double y) {
        double clamped = Math.max(0, Math.min(maxScroll(), y));
        if (clamped == scrollY) return;
        scrollY = clamped;
        vbar.setValue(clamped);   // no-op if this came from the bar itself
        drawBody();
    }

    private double contentHeight() { return TOP_PAD + BOTTOM_PAD + rowH * renderRows.size(); }

    private double maxScroll() { return Math.max(0, contentHeight() - bodyCanvas.getHeight()); }

    /** Recompute row height, binning and scrollbar range, then repaint. */
    private void relayout() {
        double width  = plotArea.getWidth();
        double height = plotArea.getHeight();
        if (width <= 0 || height <= 0) return;

        bodyCanvas.setWidth(width);
        bodyCanvas.setHeight(height);
        axisCanvas.setWidth(width);

        double availH = Math.max(MIN_ROW_H, height - TOP_PAD - BOTTOM_PAD);
        int n = bars.size();

        if (n == 0) {
            renderRows = List.of();
            rowH = 12;
        } else if (zoomRowH == null) {
            // Fit: bin only as much as is needed to get every song on screen.
            int maxRows = Math.max(1, (int) Math.floor(availH / MIN_ROW_H));
            int binSize = Math.max(1, (int) Math.ceil(n / (double) maxRows));
            renderRows = buildRows(binSize);
            rowH = clampRowH(availH / renderRows.size());
        } else {
            renderRows = buildRows(1);
            rowH = clampRowH(zoomRowH);
        }

        double max = maxScroll();
        vbar.setMax(max);
        // visibleAmount == viewport height makes the thumb exactly
        // viewportH/contentH of the track, which is what a reader expects.
        vbar.setVisibleAmount(height);
        vbar.setUnitIncrement(Math.max(8, rowH * 3));
        vbar.setBlockIncrement(height);
        vbar.setVisible(max > 0);
        vbar.setManaged(max > 0);

        scrollY = Math.max(0, Math.min(max, scrollY));
        vbar.setValue(scrollY);

        if (hoverRow >= renderRows.size()) hoverRow = -1;

        drawAxis();
        drawBody();
        updateReadout();
    }

    /**
     * Group songs into painted rows of {@code binSize}, each remembering
     * its highest-played member so a band's spike stays visible and
     * attributable.
     */
    private List<RenderRow> buildRows(int binSize) {
        List<RenderRow> out = new ArrayList<>((bars.size() + binSize - 1) / binSize);
        for (int from = 0; from < bars.size(); from += binSize) {
            int to = Math.min(bars.size(), from + binSize);
            int rep = from;
            for (int i = from; i < to; i++) {
                Bar candidate = bars.get(i);
                Bar best = bars.get(rep);
                // Prefer a song with real data; among those, the biggest.
                if (!best.hasData() && candidate.hasData()) rep = i;
                else if (candidate.hasData() && best.hasData() && candidate.plays() > best.plays()) rep = i;
            }
            out.add(new RenderRow(from, to, rep));
        }
        return out;
    }

    private double plotWidth() { return Math.max(1, bodyCanvas.getWidth() - LEFT_GUTTER - RIGHT_PAD); }

    /** Painted-row index under a canvas-relative y, accounting for scroll. */
    private int rowAt(double y) {
        if (renderRows.isEmpty() || rowH <= 0) return -1;
        int idx = (int) Math.floor((y + scrollY - TOP_PAD) / rowH);
        return (idx < 0 || idx >= renderRows.size()) ? -1 : idx;
    }

    // ==================================================================
    //  Painting
    // ==================================================================

    /**
     * The play-count scale, plus both axis captions. Its own canvas above
     * the plot so it stays put while a long playlist scrolls — an axis
     * drawn at the bottom of the plot would scroll out of sight, which is
     * where it's least useful.
     */
    private void drawAxis() {
        GraphicsContext g = axisCanvas.getGraphicsContext2D();
        double w = axisCanvas.getWidth();
        g.clearRect(0, 0, w, AXIS_HEIGHT);
        g.setFont(LABEL_FONT);

        g.setFill(TEXT_DIM);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("song ↓", LEFT_GUTTER - 8, 12);
        // No "→" here: the horizontal arrow doesn't render in this font
        // stack (the vertical one does), and the ascending tick labels
        // below already say which way the axis runs.
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("plays", LEFT_GUTTER + 2, 12);

        double plotW = plotWidth();
        g.setFill(TEXT_MUTED);
        g.setTextAlign(TextAlignment.CENTER);
        for (long t : ticks()) {
            double x = LEFT_GUTTER + (t / (double) niceMax) * plotW;
            g.fillText(compact(t), Math.min(w - 14, x), AXIS_HEIGHT - 9);
        }

        g.setStroke(GRID_STRONG);
        g.setLineWidth(1);
        g.strokeLine(LEFT_GUTTER - 0.5, AXIS_HEIGHT - 3.5, w - RIGHT_PAD, AXIS_HEIGHT - 3.5);
    }

    /** Paints only the rows intersecting the viewport. */
    private void drawBody() {
        GraphicsContext g = bodyCanvas.getGraphicsContext2D();
        double w = bodyCanvas.getWidth();
        double h = bodyCanvas.getHeight();
        g.clearRect(0, 0, w, h);

        if (renderRows.isEmpty()) {
            g.setFill(TEXT_MUTED);
            g.setFont(LABEL_FONT);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText("Nothing to graph.", LEFT_GUTTER, TOP_PAD + 20);
            return;
        }

        double plotW = plotWidth();
        // Plot extent in canvas coordinates, clipped to what's on screen.
        double plotTop    = Math.max(0, TOP_PAD - scrollY);
        double plotBottom = Math.min(h, TOP_PAD + rowH * renderRows.size() - scrollY);

        // ----- Vertical gridlines, behind the bars -----
        g.setLineWidth(1);
        for (long t : ticks()) {
            double x = Math.floor(LEFT_GUTTER + (t / (double) niceMax) * plotW) + 0.5;
            g.setStroke(t == 0 ? GRID_STRONG : GRID);
            g.strokeLine(x, plotTop, x, plotBottom);
        }

        // Binned rows are drawn edge-to-edge (a continuous density band);
        // unbinned rows get a gap so individual songs read as separate bars.
        double barH = binned() ? rowH : Math.max(1, rowH <= 4 ? rowH : rowH - Math.min(4, rowH * 0.28));
        boolean labelSongs = !binned() && rowH >= 15;

        // Gutter numbers are stepped by PIXELS, not by songs, so they stay
        // present and evenly spaced whether a row is one song or forty. A
        // song-count step went blank entirely once rows got thin — which
        // is exactly when a Y axis is most needed to locate a spike.
        int numberStep = Math.max(1, (int) Math.ceil(24 / Math.max(0.5, rowH)));

        int firstRow = Math.max(0, (int) Math.floor((scrollY - TOP_PAD) / rowH));
        int lastRow  = Math.min(renderRows.size(),
                (int) Math.ceil((scrollY + h - TOP_PAD) / rowH) + 1);

        g.setFont(LABEL_FONT);
        for (int r = firstRow; r < lastRow; r++) {
            RenderRow rr = renderRows.get(r);
            Bar b = bars.get(rr.rep());
            double rowTop = TOP_PAD + r * rowH - scrollY;
            double y = rowTop + (rowH - barH) / 2;

            if (r == hoverRow) {
                g.setFill(ROW_HILITE);
                g.fillRect(0, rowTop, w, rowH);
            }

            double barW;
            if (!b.hasData()) {
                barW = 3;
                g.setFill(NO_DATA);
                g.fillRect(LEFT_GUTTER, y, barW, barH);
            } else if (b.plays() <= 0) {
                // A confirmed zero still gets a tick, so "never played"
                // doesn't render identically to "never fetched".
                barW = 2;
                g.setFill(NO_DATA);
                g.fillRect(LEFT_GUTTER, y, barW, barH);
            } else {
                barW = Math.max(1.5, (b.plays() / (double) niceMax) * plotW);
                boolean isTop = actualMax > 0 && b.plays() == actualMax;
                g.setFill(r == hoverRow ? BAR_HOVER : (isTop ? BAR_TOP : BAR));
                g.fillRect(LEFT_GUTTER, y, barW, barH);
            }

            if (labelSongs) {
                drawRowLabel(g, b, r, barW, rowTop, w);
            }

            int shownPosition = bars.get(rr.from()).position();
            if (r == hoverRow || r % numberStep == 0) {
                g.setFont(r == hoverRow ? LABEL_FONT_BOLD : LABEL_FONT);
                g.setFill(r == hoverRow ? Color.WHITE : TEXT_DIM);
                g.setTextAlign(TextAlignment.RIGHT);
                g.fillText(String.valueOf(shownPosition), LEFT_GUTTER - 8, rowTop + rowH / 2 + 4);
            }
        }

        // Left axis line.
        g.setStroke(GRID_STRONG);
        g.setLineWidth(1);
        g.strokeLine(LEFT_GUTTER - 0.5, plotTop, LEFT_GUTTER - 0.5, plotBottom);
    }

    /**
     * "Title  1,234" beside the bar, or tucked inside its right end when
     * the bar is long enough that there's no room left. Canvas fillText
     * with a maxWidth SQUEEZES the glyphs rather than clipping them, so
     * without this the longest bars — the ones the reader most wants to
     * identify — got an illegibly compressed label.
     */
    private void drawRowLabel(GraphicsContext g, Bar b, int row, double barW, double rowTop, double canvasW) {
        String text = b.hasData()
                ? b.title() + "   " + String.format("%,d", b.plays())
                : b.title() + "   no data";
        double baseline = rowTop + rowH / 2 + 4;
        double outsideX = LEFT_GUTTER + barW + 6;
        double outsideRoom = canvasW - RIGHT_PAD - outsideX;

        g.setFont(row == hoverRow ? LABEL_FONT_BOLD : LABEL_FONT);
        if (outsideRoom >= 70 || barW < 90) {
            g.setFill(row == hoverRow ? Color.WHITE : TEXT_MUTED);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(text, outsideX, baseline, Math.max(10, outsideRoom));
        } else {
            g.setFill(LABEL_ON_BAR);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(text, LEFT_GUTTER + barW - 8, baseline, barW - 16);
        }
    }

    /** Five evenly-spaced X ticks from 0 to {@link #niceMax}. */
    private long[] ticks() {
        int divisions = 4;
        long[] out = new long[divisions + 1];
        for (int i = 0; i <= divisions; i++) {
            out[i] = Math.round(niceMax * (i / (double) divisions));
        }
        return out;
    }

    /**
     * Round a max up to a readable 1/2/2.5/3/4/5×10^n boundary. The
     * ladder is deliberately finer than the usual 1/2/5: with only
     * 1/2/5 a max of 349 rounds to 500 and throws away a third of the
     * plot width.
     */
    private static long niceCeiling(long max) {
        if (max <= 4) return Math.max(1, max);
        double mag = Math.pow(10, Math.floor(Math.log10(max)));
        double norm = max / mag;
        double stepped = norm <= 1 ? 1
                : norm <= 2 ? 2
                : norm <= 2.5 ? 2.5
                : norm <= 3 ? 3
                : norm <= 4 ? 4
                : norm <= 5 ? 5
                : norm <= 8 ? 8
                : 10;
        return (long) Math.ceil(stepped * mag);
    }

    /** 1200 → "1.2k", 15000 → "15k". Keeps the axis strip narrow. */
    private static String compact(long v) {
        if (v < 1000) return String.valueOf(v);
        if (v < 10000) return String.format("%.1fk", v / 1000.0).replace(".0k", "k");
        if (v < 1_000_000) return (v / 1000) + "k";
        return String.format("%.1fM", v / 1_000_000.0).replace(".0M", "M");
    }
}
