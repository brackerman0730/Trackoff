package com.rankify.io.lastfm;

import com.rankify.config.Settings;

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