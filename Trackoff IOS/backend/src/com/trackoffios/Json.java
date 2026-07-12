package com.trackoffios;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny hand-rolled JSON toolkit — both a reader (for parsing Spotify/
 * Last.fm/Deezer responses) and a writer (for building this server's own
 * API responses), consolidated from the desktop app's SpotifyPlaylistSource
 * and LibraryView, which each had their own near-identical copies. Same
 * "no third-party dependency" style as the rest of Trackoff.
 */
public final class Json {

    private Json() {}

    // ==================================================================
    //  Reading
    // ==================================================================

    public static String string(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    public static String stringOrNull(String json, String key) {
        String v = string(json, key);
        return v.isEmpty() && !json.contains("\"" + key + "\"") ? null : v;
    }

    public static int intVal(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public static boolean boolVal(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && m.group(1).equals("true");
    }

    public static List<String> allStrings(String json, String key) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    public static String objectBlock(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":{");
        if (idx < 0) return "";
        int start = json.indexOf('{', idx);
        int end = matchBrace(json, start);
        return end < 0 ? "" : json.substring(start, end + 1);
    }

    public static String arrayBlock(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":[");
        if (idx < 0) return "";
        int start = json.indexOf('[', idx);
        int end = matchBracket(json, start);
        return end < 0 ? "" : json.substring(start, end + 1);
    }

    public static int matchBrace(String s, int openIdx)   { return matchDelim(s, openIdx, '{', '}'); }
    public static int matchBracket(String s, int openIdx) { return matchDelim(s, openIdx, '[', ']'); }

    private static int matchDelim(String s, int openIdx, char open, char close) {
        if (openIdx < 0) return -1;
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\' && i + 1 < s.length()) { i++; continue; }
                if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == open) depth++;
                else if (c == close) { depth--; if (depth == 0) return i; }
            }
        }
        return -1;
    }

    /** Split a JSON array's inner content into its top-level {...} objects. */
    public static List<String> splitObjects(String arrayInner) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int i = 0; i < arrayInner.length(); i++) {
            char c = arrayInner.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) out.add(arrayInner.substring(start, i + 1)); }
        }
        return out;
    }

    // ==================================================================
    //  Writing
    // ==================================================================

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** {@code "key":"value"} with proper escaping — value may be null (renders as {@code null}). */
    public static String field(String key, String value) {
        return "\"" + esc(key) + "\":" + (value == null ? "null" : "\"" + esc(value) + "\"");
    }

    public static String field(String key, long value) {
        return "\"" + esc(key) + "\":" + value;
    }

    public static String field(String key, boolean value) {
        return "\"" + esc(key) + "\":" + value;
    }
}
