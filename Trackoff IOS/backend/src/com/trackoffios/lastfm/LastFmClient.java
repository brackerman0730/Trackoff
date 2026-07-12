package com.trackoffios.lastfm;

import com.trackoffios.Json;
import com.trackoffios.SettingsStore;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Last.fm client, ported from the desktop app's LastFmClient — including
 * the rate limiting + retry logic that was added after live testing on
 * a ~1400-song library showed Last.fm starts returning "Rate Limit
 * Exceeded" under unthrottled concurrent load, and the search-first
 * (not direct track.getinfo) matching strategy that avoids Last.fm
 * silently resolving a mismatched artist/title to the wrong track.
 */
public final class LastFmClient {

    private static final String BASE = "https://ws.audioscrobbler.com/2.0/";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final long RATE_INTERVAL_MS = 300;
    private static final Object RATE_LOCK = new Object();
    private static long nextAllowedRequestAt = 0;

    private static final int  MAX_RETRIES = 6;
    private static final long RETRY_BASE_DELAY_MS = 2000;
    private static final long RETRY_MAX_DELAY_MS = 32000;

    public record UserInfo(String username, String realName, long playcount, String imageUrl) {}
    public record TrackMatch(String name, String artist) {}

    private LastFmClient() {}

    public static UserInfo fetchUserInfo(String username, String apiKey) throws Exception {
        String url = BASE + "?method=user.getinfo&user=" + enc(username) + "&api_key=" + enc(apiKey) + "&format=json";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        if (resp.statusCode() / 100 != 2 || body.contains("\"error\"")) {
            String msg = Json.stringOrNull(body, "message");
            throw new RuntimeException("Last.fm rejected the request: " + (msg != null ? msg : "HTTP " + resp.statusCode()));
        }
        String name = Json.stringOrNull(body, "name");
        if (name == null) throw new RuntimeException("Unexpected Last.fm response");
        String realname = Json.stringOrNull(body, "realname");
        String playcount = Json.stringOrNull(body, "playcount");
        return new UserInfo(name, realname == null ? "" : realname, playcount == null ? 0L : Long.parseLong(playcount), "");
    }

    public static Optional<Long> fetchTrackPlaycount(String artist, String track, String username, String apiKey) throws Exception {
        String url = BASE + "?method=track.getinfo"
                + "&artist=" + enc(artist) + "&track=" + enc(track)
                + "&username=" + enc(username) + "&api_key=" + enc(apiKey) + "&format=json";

        for (int attempt = 0; ; attempt++) {
            awaitRateLimit();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            Integer errorCode = jsonIntOrNull(body, "error");
            if (errorCode != null) {
                if (errorCode == 6) return Optional.empty();
                if (attempt < MAX_RETRIES) { sleepBackoff(attempt); continue; }
                throw new RuntimeException("Last.fm rejected after retries: error " + errorCode);
            }
            if (resp.statusCode() / 100 != 2) {
                if (attempt < MAX_RETRIES) { sleepBackoff(attempt); continue; }
                throw new RuntimeException("Last.fm HTTP " + resp.statusCode() + " after retries");
            }
            String playcount = Json.stringOrNull(body, "userplaycount");
            return Optional.of(playcount == null ? 0L : Long.parseLong(playcount));
        }
    }

    public static Optional<Long> fetchLinkedTrackPlaycount(String artist, String track) throws Exception {
        String user = SettingsStore.get("lastfm.username");
        String key = SettingsStore.get("lastfm.api_key");
        if (user == null || key == null) throw new IllegalStateException("Last.fm not linked");
        return fetchTrackPlaycount(artist, track, user, key);
    }

    public static Optional<TrackMatch> searchTrack(String track, String artistHint, String apiKey) throws Exception {
        String url = BASE + "?method=track.search&track=" + enc(track)
                + (artistHint == null || artistHint.isBlank() ? "" : "&artist=" + enc(artistHint))
                + "&api_key=" + enc(apiKey) + "&format=json&limit=1";

        for (int attempt = 0; ; attempt++) {
            awaitRateLimit();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            if (resp.statusCode() / 100 != 2) {
                if (attempt < MAX_RETRIES) { sleepBackoff(attempt); continue; }
                throw new RuntimeException("Last.fm search HTTP " + resp.statusCode() + " after retries");
            }
            Integer errorCode = jsonIntOrNull(body, "error");
            if (errorCode != null) {
                if (attempt < MAX_RETRIES) { sleepBackoff(attempt); continue; }
                throw new RuntimeException("Last.fm search rejected after retries: error " + errorCode);
            }

            int arrKey = body.indexOf("\"track\"");
            if (arrKey < 0) return Optional.empty();
            int bracket = body.indexOf('[', arrKey);
            if (bracket < 0) return Optional.empty();
            int braceStart = body.indexOf('{', bracket);
            if (braceStart < 0) return Optional.empty();
            int braceEnd = Json.matchBrace(body, braceStart);
            if (braceEnd < 0) return Optional.empty();

            String obj = body.substring(braceStart, braceEnd + 1);
            String name = Json.stringOrNull(obj, "name");
            String artist = Json.stringOrNull(obj, "artist");
            if (name == null || artist == null) return Optional.empty();
            return Optional.of(new TrackMatch(name, artist));
        }
    }

    public static Optional<TrackMatch> searchLinkedTrack(String track, String artistHint) throws Exception {
        String key = SettingsStore.get("lastfm.api_key");
        if (key == null) throw new IllegalStateException("Last.fm not linked");
        return searchTrack(track, artistHint, key);
    }

    /** Parse a Last.fm track URL (last.fm/music/Artist/_/Track) into (artist, track) — Last.fm uses "+" for spaces in the path, not %20. */
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
        for (int i = 1; i < parts.length; i++) if (parts[i].equals("_")) { underscoreIdx = i; break; }
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

    public static boolean isLinked() {
        return SettingsStore.get("lastfm.username") != null && SettingsStore.get("lastfm.api_key") != null;
    }

    public static void saveCredentials(String username, String apiKey, long playcount) {
        SettingsStore.set("lastfm.username", username);
        SettingsStore.set("lastfm.api_key", apiKey);
        SettingsStore.set("lastfm.playcount", Long.toString(playcount));
    }

    public static void clearCredentials() {
        SettingsStore.remove("lastfm.username");
        SettingsStore.remove("lastfm.api_key");
        SettingsStore.remove("lastfm.playcount");
    }

    private static void awaitRateLimit() {
        long waitMs;
        synchronized (RATE_LOCK) {
            long now = System.currentTimeMillis();
            long start = Math.max(now, nextAllowedRequestAt);
            waitMs = start - now;
            nextAllowedRequestAt = start + RATE_INTERVAL_MS;
        }
        if (waitMs > 0) {
            try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void sleepBackoff(int attempt) {
        long base = Math.min(RETRY_BASE_DELAY_MS * (1L << Math.min(attempt, 4)), RETRY_MAX_DELAY_MS);
        long jitter = (long) (base * 0.2 * Math.random());
        try { Thread.sleep(base + jitter); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static Integer jsonIntOrNull(String j, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(j);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }
}
