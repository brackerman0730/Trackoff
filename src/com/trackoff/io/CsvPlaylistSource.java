package com.trackoff.io;

import com.trackoff.model.Playlist;
import com.trackoff.model.Song;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses standard comma-separated CSV exports produced by tools like
 * TuneMyMusic, Exportify, and Spotify's own library export.
 *
 * The first row is expected to be a header. We map columns by *aliases*
 * rather than exact names, so files from different exporters "just work"
 * without user configuration.
 *
 * Handles:
 *   - Quoted fields containing commas ("Them Vs. You Vs. Me, Deluxe")
 *   - Escaped quotes inside fields ("He said ""hi""")
 *   - UTF-8 BOM at the start of the file
 *   - Missing or reordered columns
 */
public final class CsvPlaylistSource implements PlaylistSource {

    // ------------------------------------------------------------------
    //  Header aliases — first match wins. Case-insensitive.
    // ------------------------------------------------------------------
    private static final String[] TITLE_ALIASES = {
            "Song", "Track name", "Track Name", "Title", "Name"
    };
    private static final String[] ARTIST_ALIASES = {
            "Artist", "Artist name", "Artist Name",
            "Artist name(s)", "Artist Name(s)", "Artists"
    };
    private static final String[] ALBUM_ALIASES = {
            "Album", "Album Name", "Album name"
    };
    private static final String[] ALBUM_DATE_ALIASES = {
            "Album Date", "Album Release Date", "Release Date"
    };
    private static final String[] ID_ALIASES = {
            "Spotify Track Id", "Spotify - id", "Spotify ID",
            "Track ID", "Track Id", "ID", "URI", "Track URI"
    };
    private static final String[] DURATION_ALIASES = {
            "Duration", "Track Duration", "Length"
    };
    private static final String[] DURATION_MS_ALIASES = {
            "Duration (ms)", "Track Duration (ms)", "Duration ms"
    };
    private static final String[] BPM_ALIASES        = { "BPM", "Tempo" };
    private static final String[] POPULARITY_ALIASES = { "Popularity" };
    private static final String[] ENERGY_ALIASES     = { "Energy" };
    private static final String[] KEY_ALIASES        = { "Key" };
    private static final String[] CAMELOT_ALIASES    = { "Camelot" };
    private static final String[] GENRES_ALIASES     = { "Genres", "Genre" };
    private static final String[] EXPLICIT_ALIASES   = { "Explicit" };

    // ------------------------------------------------------------------

    @Override
    public Playlist load(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            return new Playlist(pathName(path), List.of());
        }

        // Strip UTF-8 BOM if present.
        String headerLine = lines.get(0);
        if (!headerLine.isEmpty() && headerLine.charAt(0) == '\uFEFF') {
            headerLine = headerLine.substring(1);
        }

        List<String> headers = parseCsvLine(headerLine);
        Map<String, Integer> col = new HashMap<>();
        for (int k = 0; k < headers.size(); k++) {
            // Lower-case for case-insensitive matching.
            col.put(headers.get(k).trim().toLowerCase(), k);
        }

        List<Song> songs = new ArrayList<>();
        for (int lineNum = 1; lineNum < lines.size(); lineNum++) {
            String raw = lines.get(lineNum);
            if (raw.isBlank()) continue;

            List<String> fields = parseCsvLine(raw);
            Song song = buildSong(fields, col, lineNum);
            if (song != null) songs.add(song);
        }

        return new Playlist(pathName(path), songs);
    }

    // ------------------------------------------------------------------

    private Song buildSong(List<String> fields, Map<String, Integer> col, int lineNum) {
        String title  = firstMatch(fields, col, TITLE_ALIASES);
        String artist = firstMatch(fields, col, ARTIST_ALIASES);
        String id     = firstMatch(fields, col, ID_ALIASES);

        if (title.isEmpty()) return null;             // skip malformed rows
        if (id.isEmpty())    id = "row-" + lineNum;
        if (artist.isEmpty()) artist = "Unknown Artist";

        // Handle either "3:28" style or millisecond durations.
        int durationSecs = parseDuration(firstMatch(fields, col, DURATION_ALIASES));
        if (durationSecs == 0) {
            int ms = parseInt(firstMatch(fields, col, DURATION_MS_ALIASES));
            durationSecs = ms / 1000;
        }

        return Song.builder()
                .id(id)
                .title(title)
                .artist(artist)
                .album(firstMatch(fields, col, ALBUM_ALIASES))
                .albumDate(firstMatch(fields, col, ALBUM_DATE_ALIASES))
                .durationSeconds(durationSecs)
                .bpm(parseInt(firstMatch(fields, col, BPM_ALIASES)))
                .popularity(parseInt(firstMatch(fields, col, POPULARITY_ALIASES)))
                .energy(parseInt(firstMatch(fields, col, ENERGY_ALIASES)))
                .key(firstMatch(fields, col, KEY_ALIASES))
                .camelot(firstMatch(fields, col, CAMELOT_ALIASES))
                .genres(firstMatch(fields, col, GENRES_ALIASES))
                .explicit(firstMatch(fields, col, EXPLICIT_ALIASES).equalsIgnoreCase("yes")
                       || firstMatch(fields, col, EXPLICIT_ALIASES).equalsIgnoreCase("true"))
                .build();
    }

    /** Try each alias in order and return the first non-empty value. */
    private String firstMatch(List<String> fields, Map<String, Integer> col, String[] aliases) {
        for (String name : aliases) {
            Integer idx = col.get(name.toLowerCase());
            if (idx == null || idx >= fields.size()) continue;
            String value = fields.get(idx).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    // ------------------------------------------------------------------
    //  CSV line parser: handles quoted fields and escaped quotes ("")
    // ------------------------------------------------------------------
    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(field.toString());
                    field.setLength(0);
                } else if (c == '"' && field.length() == 0) {
                    inQuotes = true;
                } else {
                    field.append(c);
                }
            }
        }
        out.add(field.toString());
        return out;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.strip()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Parse "MM:SS" or "H:MM:SS" into total seconds. Returns 0 if unparseable. */
    private static int parseDuration(String s) {
        if (s == null || s.isBlank()) return 0;
        String[] parts = s.split(":");
        try {
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600
                     + Integer.parseInt(parts[1]) * 60
                     + Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) { }
        return 0;
    }

    private static String pathName(Path p) {
        return p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    }
}