package com.trackoffios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the same CSV shapes the desktop app's CsvPlaylistSource does
 * (Exportify/TuneMyMusic/Spotify-library-export style — header aliases,
 * quoted fields, escaped quotes, UTF-8 BOM), producing the same track
 * JSON shape SpotifyPlaylists does so the frontend doesn't need to care
 * which source a playlist came from.
 */
public final class CsvParser {

    private static final String[] TITLE_ALIASES  = {"Song", "Track name", "Track Name", "Title", "Name"};
    private static final String[] ARTIST_ALIASES = {"Artist", "Artist name", "Artist Name", "Artist name(s)", "Artist Name(s)", "Artists"};
    private static final String[] ALBUM_ALIASES  = {"Album", "Album Name", "Album name"};
    private static final String[] ID_ALIASES     = {"Spotify Track Id", "Spotify - id", "Spotify ID", "Track ID", "Track Id", "ID", "URI", "Track URI"};
    private static final String[] DURATION_ALIASES = {"Duration", "Track Duration", "Length"};
    private static final String[] DURATION_MS_ALIASES = {"Duration (ms)", "Track Duration (ms)", "Duration ms"};

    private CsvParser() {}

    public static String parseToJson(String csvText) {
        List<String> lines = csvText.lines().toList();
        if (lines.isEmpty()) return "[]";

        String headerLine = lines.get(0);
        if (!headerLine.isEmpty() && headerLine.charAt(0) == '﻿') headerLine = headerLine.substring(1);

        List<String> headers = parseLine(headerLine);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) col.put(headers.get(i).trim().toLowerCase(), i);

        List<String> items = new ArrayList<>();
        for (int lineNum = 1; lineNum < lines.size(); lineNum++) {
            String raw = lines.get(lineNum);
            if (raw.isBlank()) continue;
            List<String> fields = parseLine(raw);

            String title = firstMatch(fields, col, TITLE_ALIASES);
            if (title.isEmpty()) continue;
            String artist = firstMatch(fields, col, ARTIST_ALIASES);
            if (artist.isEmpty()) artist = "Unknown Artist";
            String id = firstMatch(fields, col, ID_ALIASES);
            if (id.isEmpty()) id = "csv-row-" + lineNum;
            String album = firstMatch(fields, col, ALBUM_ALIASES);

            int durationMs = parseDurationMs(firstMatch(fields, col, DURATION_ALIASES));
            if (durationMs == 0) durationMs = parseInt(firstMatch(fields, col, DURATION_MS_ALIASES));

            items.add("{" + String.join(",",
                    Json.field("id", id),
                    Json.field("title", title),
                    Json.field("artist", artist),
                    Json.field("album", album),
                    Json.field("imageUrl", ""),
                    Json.field("previewUrl", ""),
                    Json.field("durationMs", durationMs),
                    Json.field("popularity", 0),
                    Json.field("explicit", false)) + "}");
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String firstMatch(List<String> fields, Map<String, Integer> col, String[] aliases) {
        for (String name : aliases) {
            Integer idx = col.get(name.toLowerCase());
            if (idx == null || idx >= fields.size()) continue;
            String v = fields.get(idx).trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else field.append(c);
            } else {
                if (c == ',') { out.add(field.toString()); field.setLength(0); }
                else if (c == '"' && field.length() == 0) inQuotes = true;
                else field.append(c);
            }
        }
        out.add(field.toString());
        return out;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.strip()); } catch (Exception e) { return 0; }
    }

    private static int parseDurationMs(String s) {
        if (s == null || s.isBlank()) return 0;
        String[] parts = s.split(":");
        try {
            if (parts.length == 2) return (Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1])) * 1000;
            if (parts.length == 3) return (Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2])) * 1000;
        } catch (NumberFormatException ignored) {}
        return 0;
    }
}
