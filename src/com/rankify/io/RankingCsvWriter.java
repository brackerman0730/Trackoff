package com.rankify.io;

import com.rankify.model.Song;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes a finished ranking out as a standard comma-separated CSV. */
public final class RankingCsvWriter {

    private static final String[] HEADERS = {
        "Rank", "Song", "Artist", "Album", "Album Date",
        "Duration", "BPM", "Key", "Camelot", "Energy",
        "Popularity", "Genres", "Explicit", "Spotify Track Id"
    };

    public void write(Path file, List<Song> ranking) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
            out.println(String.join(",", HEADERS));
            for (int i = 0; i < ranking.size(); i++) {
                Song s = ranking.get(i);
                out.println(String.join(",",
                        String.valueOf(i + 1),
                        csv(s.title()),
                        csv(s.artist()),
                        csv(s.album()),
                        csv(s.albumDate()),
                        csv(s.formattedDuration()),
                        String.valueOf(s.bpm()),
                        csv(s.key()),
                        csv(s.camelot()),
                        String.valueOf(s.energy()),
                        String.valueOf(s.popularity()),
                        csv(s.genres()),
                        s.explicit() ? "yes" : "no",
                        csv(s.id())
                ));
            }
        }
    }

    /** Quote a CSV field if it contains a comma, quote, or newline. */
    private String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}