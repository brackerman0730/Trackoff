package com.trackoff.io.spotify;

import com.trackoff.config.Settings;
import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Spotify OAuth Authorization Code flow, hand-rolled.
 *
 * Two operations:
 *   {@link #login()}    — first-time authorization. Pops the browser,
 *                         listens on a loopback port for the redirect,
 *                         exchanges the code for tokens, saves them.
 *   {@link #refresh()}  — swap an expired access token for a new one
 *                         using the stored refresh token.
 *
 * Requires that {@link Settings#SPOTIFY_CLIENT_ID} and
 * {@link Settings#SPOTIFY_CLIENT_SECRET} are set — those come from the
 * user's own Spotify developer app.
 */
public final class SpotifyAuth {

    /** Scopes we ask the user to grant. */
    private static final String SCOPES = String.join(" ",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-public",
            "playlist-modify-private",
            "user-library-read",
            "user-read-private");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private SpotifyAuth() {}

    // ==================================================================
    //  Initial login
    // ==================================================================

    /**
     * Begin the OAuth dance. Blocks (in the caller's thread) until the
     * user finishes in the browser and we've exchanged the code for
     * tokens — typically 5–30 seconds. Callers should invoke this off
     * the JavaFX application thread.
     *
     * @throws Exception if any step fails (bad creds, user denied,
     *                   redirect URI mismatch, ports blocked, etc.)
     */
    public static void login() throws Exception {
        String clientId = requireCred(Settings.SPOTIFY_CLIENT_ID,
                "Set your Spotify Client ID first.");
        String clientSecret = requireCred(Settings.SPOTIFY_CLIENT_SECRET,
                "Set your Spotify Client Secret first.");

        String state = randomState();

        // Bind the callback server first — we need the port for the URL.
        try (OAuthCallbackServer callback = new OAuthCallbackServer(state)) {
            String redirect = callback.redirectUri();

            String authUrl = "https://accounts.spotify.com/authorize"
                    + "?response_type=code"
                    + "&client_id="    + enc(clientId)
                    + "&scope="        + enc(SCOPES)
                    + "&redirect_uri=" + enc(redirect)
                    + "&state="        + enc(state);

            openBrowser(authUrl);

            String code = callback.awaitCode().get();  // blocks

            Tokens t = exchangeCodeForTokens(code, redirect, clientId, clientSecret);
            TokenStore.save(t.toStoreTokens());
        }
    }

    // ==================================================================
    //  Refresh
    // ==================================================================

    /**
     * Trade the stored refresh token for a new access token. Called
     * automatically by {@link SpotifyApi} when tokens are about to
     * expire. Updates the DB in place.
     *
     * @return the new access token (also stored)
     * @throws IllegalStateException if the user isn't logged in
     */
    public static String refresh() throws Exception {
        var stored = TokenStore.load()
                .orElseThrow(() -> new IllegalStateException(
                        "Not logged in — call SpotifyAuth.login() first."));

        String clientId     = requireCred(Settings.SPOTIFY_CLIENT_ID, null);
        String clientSecret = requireCred(Settings.SPOTIFY_CLIENT_SECRET, null);

        String body = "grant_type=refresh_token"
                + "&refresh_token=" + enc(stored.refreshToken());

        HttpResponse<String> resp = postToken(body, clientId, clientSecret);
        Tokens t = Tokens.parse(resp.body(), stored.refreshToken(), stored.scope());
        TokenStore.save(t.toStoreTokens());
        return t.accessToken;
    }

    // ==================================================================
    //  Internals
    // ==================================================================

    private static Tokens exchangeCodeForTokens(String code, String redirect,
                                                String clientId, String clientSecret)
            throws Exception {
        String body = "grant_type=authorization_code"
                + "&code="         + enc(code)
                + "&redirect_uri=" + enc(redirect);

        HttpResponse<String> resp = postToken(body, clientId, clientSecret);
        return Tokens.parse(resp.body(), /*fallbackRefresh=*/null, /*fallbackScope=*/null);
    }

    private static HttpResponse<String> postToken(String formBody,
                                                  String clientId,
                                                  String clientSecret) throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Spotify token endpoint returned "
                    + resp.statusCode() + ": " + resp.body());
        }
        return resp;
    }

    private static void openBrowser(String url) throws Exception {
        // Desktop.browse works on Windows in almost every case; fall
        // back to `rundll32 url.dll,FileProtocolHandler` if it doesn't.
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {}
        new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String randomState() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String requireCred(String key, String friendlyMessage) {
        return Settings.get(key).orElseThrow(() -> new IllegalStateException(
                friendlyMessage != null ? friendlyMessage
                                        : "Missing setting: " + key));
    }

    // ------------------------------------------------------------------

    /**
     * Minimal token record used within this class, and the JSON parser
     * that produces it. We don't want to pull in Jackson or Gson for
     * five keys — hand-rolled string scans do the job.
     */
    private record Tokens(String accessToken,
                          String refreshToken,
                          Instant expiresAt,
                          String scope) {

        TokenStore.Tokens toStoreTokens() {
            return new TokenStore.Tokens(accessToken, refreshToken, expiresAt, scope);
        }

        /**
         * Parse Spotify's token JSON. Refresh responses omit the
         * refresh_token field, so we pass in a fallback to reuse the
         * previous one.
         */
        static Tokens parse(String json, String fallbackRefresh, String fallbackScope) {
            String access  = jsonString(json, "access_token");
            String refresh = jsonStringOrNull(json, "refresh_token");
            String scope   = jsonStringOrNull(json, "scope");
            int expiresIn  = jsonInt(json, "expires_in");

            if (refresh == null) refresh = fallbackRefresh;
            if (scope == null)   scope   = fallbackScope;

            if (access == null) {
                throw new RuntimeException("Token response missing access_token: " + json);
            }
            return new Tokens(
                    access,
                    refresh,
                    Instant.now().plusSeconds(expiresIn),
                    scope != null ? scope : "");
        }

        // ---- micro JSON extractors ----
        private static String jsonString(String j, String key) {
            String v = jsonStringOrNull(j, key);
            if (v == null) throw new RuntimeException("Missing key: " + key);
            return v;
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

        private static int jsonInt(String j, String key) {
            String needle = "\"" + key + "\"";
            int i = j.indexOf(needle);
            if (i < 0) throw new RuntimeException("Missing key: " + key);
            int colon = j.indexOf(':', i + needle.length());
            int start = colon + 1;
            while (start < j.length() && Character.isWhitespace(j.charAt(start))) start++;
            int end = start;
            while (end < j.length() && (Character.isDigit(j.charAt(end)) || j.charAt(end) == '-')) end++;
            return Integer.parseInt(j.substring(start, end));
        }
    }
}
