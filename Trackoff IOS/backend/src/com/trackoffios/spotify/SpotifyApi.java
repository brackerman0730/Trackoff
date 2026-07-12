package com.trackoffios.spotify;

import com.trackoffios.SettingsStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/** Thin authenticated GET wrapper around Spotify's Web API — same shape as the desktop app's SpotifyApi. */
public final class SpotifyApi {

    private static final String BASE = "https://api.spotify.com/v1";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private SpotifyApi() {}

    public static String get(String pathOrUrl) throws Exception {
        ensureFreshToken();
        String access = SettingsStore.get("spotify.access_token");
        if (access == null) throw new IllegalStateException("Not logged in to Spotify");

        HttpResponse<String> resp = doGet(pathOrUrl, access);
        if (resp.statusCode() == 401) {
            SpotifyAuth.refresh();
            resp = doGet(pathOrUrl, SettingsStore.get("spotify.access_token"));
        }
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Spotify " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private static void ensureFreshToken() throws Exception {
        String expiresAt = SettingsStore.get("spotify.expires_at");
        if (expiresAt == null) return;
        if (Instant.now().isAfter(Instant.parse(expiresAt).minusSeconds(60))) {
            SpotifyAuth.refresh();
        }
    }

    private static HttpResponse<String> doGet(String pathOrUrl, String accessToken) throws Exception {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : BASE + pathOrUrl;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
