package com.trackoff.io;

import com.trackoff.model.Playlist;
import com.trackoff.ranking.AdaptiveMergeSortRanker;
import com.trackoff.ranking.AdaptiveMergeSortRanker.Snapshot;
import com.trackoff.ranking.TransitivityCache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Save / load a ranker's state to a small plain-text file.
 *
 * Format is deliberately human-readable (and dependency-free) so it can
 * be inspected or hand-edited if anything goes wrong:
 *
 *   SOURCE      csv|spotify  <sourceId>     (optional; missing = legacy CSV)
 *   SIG         <songCount>|<idsHash>       (optional; missing = skip check)
 *   ORDER       id1,id2,id3,...
 *   CURSORS     width leftStart mid rightEnd i j bufferSize done asked saved
 *   BUFFER      id|id|id                    (omitted if no active merge)
 *   PREF        winnerId loserId            (one line per recorded preference)
 *
 * Backward compatible: files without SOURCE/SIG are treated as legacy
 * CSV sessions and load exactly like they did before.
 */
public final class ProgressStore {

    // ==================================================================
    //  Metadata about *where* a session came from — needed at resume
    //  time so we can re-fetch the playlist from the same place.
    // ==================================================================

    public enum SourceType { CSV, SPOTIFY, LEGACY_CSV }

    /**
     * Where a saved session originated. Passed into save() so we can
     * write it into the file, returned from load() so the resume flow
     * knows how to reconstitute the playlist.
     */
    public record SessionMeta(SourceType type, String sourceId, String signature) {

        public static SessionMeta forCsv(String csvPath, Playlist playlist) {
            return new SessionMeta(SourceType.CSV, csvPath, signatureOf(playlist));
        }

        public static SessionMeta forSpotify(String playlistId, Playlist playlist) {
            return new SessionMeta(SourceType.SPOTIFY, playlistId, signatureOf(playlist));
        }

        /** Legacy = older file that didn't record its source. */
        public static SessionMeta legacyCsv() {
            return new SessionMeta(SourceType.LEGACY_CSV, null, null);
        }

        /** Cheap integrity check: song count + hash of all IDs, order-independent. */
        private static String signatureOf(Playlist p) {
            long idsHash = 0;
            for (var s : p.songs()) idsHash += s.id().hashCode();  // order-independent
            return p.songs().size() + "|" + Long.toHexString(idsHash);
        }

        /**
         * Compare this saved signature against a freshly-loaded playlist.
         * Returns true if the playlist looks unchanged enough to resume.
         */
        public boolean matchesPlaylist(Playlist current) {
            if (signature == null) return true;                 // legacy — trust it
            String currentSig = signatureOf(current);
            return signature.equals(currentSig);
        }
    }

    // ==================================================================
    //  Save
    // ==================================================================

    /** Save without metadata (used only for legacy call sites; prefer overload). */
    public void save(Path file, AdaptiveMergeSortRanker ranker) throws IOException {
        save(file, ranker, null);
    }

    /**
     * Save the ranker state, plus (if provided) the session metadata so
     * a future resume flow can rebuild the playlist automatically.
     */
    public void save(Path file, AdaptiveMergeSortRanker ranker, SessionMeta meta)
            throws IOException {
        Snapshot s = ranker.snapshot();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {

            if (meta != null && meta.type() != SourceType.LEGACY_CSV) {
                out.println("SOURCE\t" + meta.type().name().toLowerCase() + "\t" + meta.sourceId());
                if (meta.signature() != null) {
                    out.println("SIG\t" + meta.signature());
                }
            }

            out.println("ORDER\t" + String.join(",", s.orderIds()));
            out.printf ("CURSORS\t%d %d %d %d %d %d %d %b %d %d%n",
                    s.width(), s.leftStart(), s.mid(), s.rightEnd(),
                    s.i(), s.j(), s.bufferSize(),
                    s.done(), s.comparisonsAsked(), s.comparisonsSkipped());

            if (s.bufferIds() != null) {
                out.println("BUFFER\t" + String.join("|", s.bufferIds()));
            }

            for (String[] pair : recordedPairs(s.cache())) {
                out.println("PREF\t" + pair[0] + "\t" + pair[1]);
            }
        }
    }

    // ==================================================================
    //  Peek — read only the metadata, without touching the ranker
    // ==================================================================

    /**
     * Peek at a session file's SOURCE/SIG lines without applying any
     * ranker state. Lets the resume flow decide *how* to reconstruct
     * the playlist before it has one to work with.
     */
    public SessionMeta peekMeta(Path file) throws IOException {
        SourceType type = SourceType.LEGACY_CSV;
        String sourceId = null;
        String signature = null;

        try (BufferedReader in = Files.newBufferedReader(file)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                // We only need the header lines; ORDER onward means header done.
                if (line.startsWith("ORDER\t")) break;

                if (line.startsWith("SOURCE\t")) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length >= 3) {
                        try {
                            type = SourceType.valueOf(parts[1].toUpperCase());
                        } catch (IllegalArgumentException ignore) {
                            type = SourceType.LEGACY_CSV;
                        }
                        sourceId = parts[2];
                    }
                } else if (line.startsWith("SIG\t")) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length >= 2) signature = parts[1];
                }
            }
        }
        return new SessionMeta(type, sourceId, signature);
    }

    // ==================================================================
    //  Load
    // ==================================================================

    /**
     * Load ranker state from a session file into the given ranker. The
     * ranker must already be constructed against the *correct* playlist
     * — callers use peekMeta() first to figure out which playlist that
     * is, load it, build the ranker, then call this.
     */
    public void load(Path file, AdaptiveMergeSortRanker ranker) throws IOException {
        String[] orderIds  = new String[0];
        String[] bufferIds = null;
        int width = 1, leftStart = 0, mid = 0, rightEnd = 0;
        int i = 0, j = 0, bufferSize = 0, asked = 0, saved = 0;
        boolean done = false;

        TransitivityCache cache = ranker.cache();

        try (BufferedReader in = Files.newBufferedReader(file)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", 2);
                switch (parts[0]) {
                    case "SOURCE", "SIG" -> { /* handled by peekMeta */ }
                    case "ORDER"   -> orderIds = parts[1].split(",");
                    case "BUFFER"  -> bufferIds = parts[1].split("\\|");
                    case "CURSORS" -> {
                        String[] c = parts[1].split(" ");
                        width      = Integer.parseInt(c[0]);
                        leftStart  = Integer.parseInt(c[1]);
                        mid        = Integer.parseInt(c[2]);
                        rightEnd   = Integer.parseInt(c[3]);
                        i          = Integer.parseInt(c[4]);
                        j          = Integer.parseInt(c[5]);
                        bufferSize = Integer.parseInt(c[6]);
                        done       = Boolean.parseBoolean(c[7]);
                        asked      = Integer.parseInt(c[8]);
                        saved      = Integer.parseInt(c[9]);
                    }
                    case "PREF" -> {
                        String[] p = parts[1].split("\t");
                        cache.recordPreference(p[0], p[1]);
                    }
                }
            }
        }

        ranker.restore(new Snapshot(
                orderIds, width, leftStart, mid, rightEnd, i, j,
                bufferIds, bufferSize, done, asked, saved, cache
        ));
    }

    // ==================================================================
    //  Placeholder — same limitation as before, documented so future-you
    //  knows resumed sessions may re-ask a few transitively-implied
    //  comparisons. Never wrong, just occasionally redundant.
    // ==================================================================

    private List<String[]> recordedPairs(TransitivityCache cache) {
        return new ArrayList<>();
    }
}