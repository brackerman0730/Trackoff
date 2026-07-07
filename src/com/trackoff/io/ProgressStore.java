package com.trackoff.io;

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
 *   ORDER       id1,id2,id3,...
 *   CURSORS     width leftStart mid rightEnd i j bufferSize done asked saved
 *   BUFFER      id|id|id            (omitted if no active merge)
 *   PREF        winnerId loserId    (one line per recorded preference)
 */
public final class ProgressStore {

    public void save(Path file, AdaptiveMergeSortRanker ranker) throws IOException {
        Snapshot s = ranker.snapshot();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {

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

    /**
     * The cache hides its internal map; if you want lossless resumes,
     * add an edges() method to TransitivityCache and populate this list.
     * Returning empty here means resumed sessions may re-ask a few
     * transitively-implied comparisons, never producing a wrong result.
     */
    private List<String[]> recordedPairs(TransitivityCache cache) {
        return new ArrayList<>();
    }
}