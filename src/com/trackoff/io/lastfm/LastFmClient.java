package com.trackoff.io.lastfm;

import com.trackoff.config.Settings;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Read-only Last.fm client.
 *
 * Last.fm's API is refreshingly simple: append an API key + username +
 * method name to a URL and you get JSON back. No OAuth, no signing —
 * as long as you don't need to *write* scrobbles.
 *
 * Phase 1 uses this for exactly one thing: verifying the user's
 * credentials and reading their total scrobble count. Phase 2 will
 * add {@code user.getRecentTracks} for the full history download.
 */
public final class LastFmClient {

    private static final String BASE = "https://ws.audioscrobbler.com/2.0/";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** What we learn from {@code user.getInfo}. */
    public record UserInfo(
            String username,
            String realName,
            long   playcount,
            String imageUrl
    ) {}

    private LastFmClient() {}

    /**
     * Verify credentials and return the user profile. Throws with a
     * friendly message if the API key or username is wrong.
     */
    public static UserInfo fetchUserInfo(String username, String apiKey) throws Exception {
        String url = BASE
                + "?method=user.getinfo"
                + "&user="    + enc(username)
                + "&api_key=" + enc(apiKey)
                + "&format=json";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET().build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();

        if (resp.statusCode() / 100 != 2 || body.contains("\"error\"")) {
            String msg = jsonStringOrNull(body, "message");
            throw new RuntimeException("Last.fm rejected the request: "
                    + (msg != null ? msg : "HTTP " + resp.statusCode()));
        }

        String name       = jsonStringOrNull(body, "name");
        String realname   = jsonStringOrNull(body, "realname");
        String playcount  = jsonStringOrNull(body, "playcount");
        String imageUrl   = firstImageUrl(body);

        if (name == null) {
            throw new RuntimeException("Unexpected Last.fm response:\n" + body);
        }
        return new UserInfo(
                name,
                realname == null ? "" : realname,
                playcount == null ? 0L : Long.parseLong(playcount),
                imageUrl);
    }

    /** Convenience: read the currently-linked user's info from Settings. */
    public static Optional<UserInfo> fetchLinkedUser() throws Exception {
        Optional<String> user = Settings.get(Settings.LASTFM_USERNAME);
        Optional<String> key  = Settings.get(Settings.LASTFM_API_KEY);
        if (user.isEmpty() || key.isEmpty()) return Optional.empty();
        return Optional.of(fetchUserInfo(user.get(), key.get()));
    }

    /**
     * How many times {@code username} has scrobbled this exact track,
     * via {@code track.getinfo}. Empty means Last.fm's "track not found"
     * (error code 6) — i.e. no match under this exact (artist, track)
     * pair, NOT necessarily zero plays; callers that want a best-effort
     * match despite messy metadata (multiple credited artists, "(feat.
     * X)" in the title, etc.) should fall back to {@link #searchTrack}.
     */
    public static Optional<Long> fetchTrackPlaycount(String artist, String track,
                                                       String username, String apiKey) throws Exception {
        String url = BASE
                + "?method=track.getinfo"
                + "&artist="   + enc(artist)
                + "&track="    + enc(track)
                + "&username=" + enc(username)
                + "&api_key="  + enc(apiKey)
                + "&format=json";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET().build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();

        Integer errorCode = jsonIntOrNull(body, "error");
        if (errorCode != null) {
            if (errorCode == 6) return Optional.empty();   // "Track not found"
            String msg = jsonStringOrNull(body, "message");
            throw new RuntimeException("Last.fm rejected the request: "
                    + (msg != null ? msg : "error " + errorCode));
        }
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Last.fm HTTP " + resp.statusCode());
        }

        String playcount = jsonStringOrNull(body, "userplaycount");
        return Optional.of(playcount == null ? 0L : Long.parseLong(playcount));
    }

    /**
     * Convenience: fetch a track's play count for the currently-linked
     * user. Throws if Last.fm isn't linked — callers in this codebase
     * only reach this after already checking {@link #isLinked()}.
     */
    public static Optional<Long> fetchLinkedTrackPlaycount(String artist, String track) throws Exception {
        Optional<String> user = Settings.get(Settings.LASTFM_USERNAME);
        Optional<String> key  = Settings.get(Settings.LASTFM_API_KEY);
        if (user.isEmpty() || key.isEmpty()) throw new IllegalStateException("Last.fm not linked");
        return fetchTrackPlaycount(artist, track, user.get(), key.get());
    }

    /** A best-guess (corrected) track match from {@code track.search}. */
    public record TrackMatch(String name, String artist) {}

    /**
     * Fuzzy track lookup via {@code track.search} — used as a fallback
     * when {@link #fetchTrackPlaycount} can't find an exact match.
     * Spotify often joins multiple credited artists with ", " and keeps
     * "(feat. X)" in the title; Last.fm's own search handles that kind
     * of messy metadata far better than an exact-string lookup does.
     * {@code artistHint} narrows the search but doesn't have to be exact.
     */
    public static Optional<TrackMatch> searchTrack(String track, String artistHint, String apiKey) throws Exception {
        String url = BASE
                + "?method=track.search"
                + "&track="   + enc(track)
                + (artistHint == null || artistHint.isBlank() ? "" : "&artist=" + enc(artistHint))
                + "&api_key=" + enc(apiKey)
                + "&format=json"
                + "&limit=1";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET().build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        if (resp.statusCode() / 100 != 2) return Optional.empty();

        int arrKey = body.indexOf("\"track\"");
        if (arrKey < 0) return Optional.empty();
        int bracket = body.indexOf('[', arrKey);
        if (bracket < 0) return Optional.empty();
        int braceStart = body.indexOf('{', bracket);
        if (braceStart < 0) return Optional.empty();
        int braceEnd = matchBrace(body, braceStart);
        if (braceEnd < 0) return Optional.empty();

        String obj    = body.substring(braceStart, braceEnd + 1);
        String name   = jsonStringOrNull(obj, "name");
        String artist = jsonStringOrNull(obj, "artist");
        if (name == null || artist == null) return Optional.empty();
        return Optional.of(new TrackMatch(name, artist));
    }

    /**
     * Parse a Last.fm track URL (e.g.
     * {@code https://www.last.fm/music/Radiohead/_/Karma+Police}) into
     * an (artist, track) pair, for the manual "paste a link to
     * reassign" override. Last.fm's own URLs use "+" for spaces in path
     * segments (confirmed via raw API responses — not the standard
     * %20), so segments are decoded accordingly rather than via plain
     * URLDecoder/URI handling alone.
     */
    public static Optional<TrackMatch> parseTrackUrl(String url) {
        if (url == null) return Optional.empty();
        String u = url.trim();
        int idx = u.indexOf("/music/");
        if (idx < 0) return Optional.empty();

        String rest = u.substring(idx + "/music/".length());
        int cut = rest.length();
        int q = rest.indexOf('?'); if (q >= 0) cut = Math.min(cut, q);
        int h = rest.indexOf('#'); if (h >= 0) cut = Math.min(cut, h);
        rest = rest.substring(0, cut);

        String[] parts = rest.split("/");
        if (parts.length < 1) return Optional.empty();
        String artist = decodeUrlSegment(parts[0]);

        int underscoreIdx = -1;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("_")) { underscoreIdx = i; break; }
        }
        if (underscoreIdx < 0 || underscoreIdx + 1 >= parts.length) return Optional.empty();
        String track = decodeUrlSegment(parts[underscoreIdx + 1]);

        if (artist.isBlank() || track.isBlank()) return Optional.empty();
        return Optional.of(new TrackMatch(track, artist));
    }

    private static String decodeUrlSegment(String s) {
        String withSpaces = s.replace('+', ' ');
        try { return java.net.URLDecoder.decode(withSpaces, StandardCharsets.UTF_8); }
        catch (Exception e) { return withSpaces; }
    }

    /** Convenience: {@link #searchTrack} using the currently-linked API key. */
    public static Optional<TrackMatch> searchLinkedTrack(String track, String artistHint) throws Exception {
        Optional<String> key = Settings.get(Settings.LASTFM_API_KEY);
        if (key.isEmpty()) throw new IllegalStateException("Last.fm not linked");
        return searchTrack(track, artistHint, key.get());
    }

    /** True if the user has linked a Last.fm account. */
    public static boolean isLinked() {
        return Settings.get(Settings.LASTFM_USERNAME).isPresent()
            && Settings.get(Settings.LASTFM_API_KEY).isPresent();
    }

    /** Persist credentials after a successful test call. */
    public static void saveCredentials(String username, String apiKey, long playcount) {
        Settings.set(Settings.LASTFM_USERNAME,  username);
        Settings.set(Settings.LASTFM_API_KEY,   apiKey);
        Settings.set(Settings.LASTFM_PLAYCOUNT, Long.toString(playcount));
    }

    /** Wipe stored Last.fm credentials. */
    public static void clearCredentials() {
        Settings.unset(Settings.LASTFM_USERNAME);
        Settings.unset(Settings.LASTFM_API_KEY);
        Settings.unset(Settings.LASTFM_PLAYCOUNT);
    }

    // ==================================================================
    //  Tiny hand-rolled JSON extractors (matching the style used
    //  elsewhere in the codebase — no third-party deps).
    // ==================================================================

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Like {@link #jsonStringOrNull} but for unquoted numeric fields (e.g. "error":6). */
    private static Integer jsonIntOrNull(String j, String key) {
        String needle = "\"" + key + "\"";
        int i = j.indexOf(needle);
        if (i < 0) return null;
        int colon = j.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < j.length() && Character.isWhitespace(j.charAt(p))) p++;
        int start = p;
        while (p < j.length() && (Character.isDigit(j.charAt(p)) || j.charAt(p) == '-')) p++;
        if (p == start) return null;
        try { return Integer.parseInt(j.substring(start, p)); }
        catch (Exception e) { return null; }
    }

    private static String jsonStringOrNull(String j, String key) {
        String needle = "\"" + key + "\"";
        int i = j.indexOf(needle);
        if (i < 0) return null;
        int colon = j.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int quote = j.indexOf('"', colon + 1);
        if (quote < 0) return null;
        StringBuilder sb = new StringBuilder();
        int k = quote + 1;
        while (k < j.length()) {
            char c = j.charAt(k);
            if (c == '\\' && k + 1 < j.length()) { sb.append(j.charAt(k + 1)); k += 2; continue; }
            if (c == '"') break;
            sb.append(c);
            k++;
        }
        return sb.toString();
    }

    /** Index of the '}' that closes the '{' at {@code openIdx}. */
    private static int matchBrace(String s, int openIdx) {
        if (openIdx < 0) return -1;
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /**
     * Last.fm returns images as an array of size objects. Pull the
     * largest ("extralarge") if present, else whatever's first.
     */
    private static String firstImageUrl(String json) {
        int arr = json.indexOf("\"image\"");
        if (arr < 0) return null;
        // Look for size":"extralarge"...#text":"..."
        int el = json.indexOf("\"extralarge\"", arr);
        int scanFrom = (el >= 0) ? el : arr;
        int text = json.indexOf("\"#text\"", scanFrom);
        if (text < 0) return null;
        int colon = json.indexOf(':', text);
        int quote = json.indexOf('"', colon + 1);
        int end   = json.indexOf('"', quote + 1);
        if (quote < 0 || end < 0) return null;
        return json.substring(quote + 1, end);
    }
}