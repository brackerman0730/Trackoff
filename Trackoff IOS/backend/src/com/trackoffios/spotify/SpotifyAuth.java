package com.trackoffios.spotify;

import com.trackoffios.SettingsStore;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spotify OAuth Authorization Code flow, adapted from the desktop app's
 * {@code SpotifyAuth} for a web redirect instead of a loopback callback
 * server — the browser navigates to Spotify, Spotify redirects back to
 * one of this same server's own routes.
 *
 * Read-only scopes only (no playlist-modify) — the desktop app confirmed
 * Spotify blocks playlist-modify writes for personal/Development-Mode
 * apps regardless of granted scope, and this build has no write features,
 * so there's no reason to request scope that can't be used.
 */
public final class SpotifyAuth {

    private static final String SCOPES = String.join(" ",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-read-private");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** In-flight state token, checked on callback to guard against CSRF. Single-user server, so one at a time is fine. */
    private static volatile String pendingState;

    private SpotifyAuth() {}

    public static String buildAuthorizeUrl(String redirectUri) {
        String clientId = SettingsStore.get("spotify.client_id");
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Spotify Client ID isn't set yet — connect it first.");
        }
        pendingState = randomState();
        return "https://accounts.spotify.com/authorize"
                + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(pendingState);
    }

    public static void handleCallback(String code, String state, String redirectUri) throws Exception {
        if (pendingState == null || !pendingState.equals(state)) {
            throw new IllegalStateException("OAuth state mismatch — please try connecting again.");
        }
        pendingState = null;
        exchangeCode(code, redirectUri);
    }

    private static void exchangeCode(String code, String redirectUri) throws Exception {
        String body = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri);
        HttpResponse<String> resp = postToken(body);
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Spotify token exchange failed: " + resp.statusCode() + " " + resp.body());
        }
        storeTokenResponse(resp.body());
    }

    public static synchronized void refresh() throws Exception {
        String refreshToken = SettingsStore.get("spotify.refresh_token");
        if (refreshToken == null) throw new IllegalStateException("Not logged in to Spotify");

        String body = "grant_type=refresh_token&refresh_token=" + enc(refreshToken);
        HttpResponse<String> resp = postToken(body);
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Spotify token refresh failed: " + resp.statusCode() + " " + resp.body());
        }
        storeTokenResponse(resp.body());
    }

    private static HttpResponse<String> postToken(String body) throws Exception {
        String clientId = SettingsStore.get("spotify.client_id");
        String clientSecret = SettingsStore.get("spotify.client_secret");
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void storeTokenResponse(String json) {
        String accessToken  = jsonString(json, "access_token");
        String refreshToken = jsonString(json, "refresh_token");   // absent on a refresh response
        String expiresIn    = jsonNumber(json, "expires_in");
        String scope        = jsonString(json, "scope");

        SettingsStore.set("spotify.access_token", accessToken);
        if (refreshToken != null) SettingsStore.set("spotify.refresh_token", refreshToken);
        if (expiresIn != null) {
            SettingsStore.set("spotify.expires_at",
                    Instant.now().plusSeconds(Long.parseLong(expiresIn)).toString());
        }
        if (scope != null) SettingsStore.set("spotify.scope", scope);
    }

    public static boolean isLinked() {
        return SettingsStore.get("spotify.refresh_token") != null;
    }

    public static void logout() {
        SettingsStore.remove("spotify.access_token");
        SettingsStore.remove("spotify.refresh_token");
        SettingsStore.remove("spotify.expires_at");
        SettingsStore.remove("spotify.scope");
    }

    private static String randomState() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String jsonString(String j, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(j);
        return m.find() ? m.group(1) : null;
    }

    static String jsonNumber(String j, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(j);
        return m.find() ? m.group(1) : null;
    }
}
