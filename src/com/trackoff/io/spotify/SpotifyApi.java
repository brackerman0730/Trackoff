package com.trackoff.io.spotify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin authenticated wrapper around Spotify's Web API.
 *
 * Callers hand in an endpoint path (e.g. {@code "/me/playlists"}) and
 * get raw JSON back. Access tokens are pulled from {@link TokenStore};
 * if they've expired we refresh transparently and retry once.
 *
 * We keep the HTTP surface tiny and hand-rolled — parsing lives in the
 * source classes that actually need the fields (see
 * {@code SpotifyPlaylistSource}).
 */
public final class SpotifyApi {

    private static final String BASE = "https://api.spotify.com/v1";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private SpotifyApi() {}

    /**
     * GET a Spotify endpoint (path or absolute URL) and return the body.
     * Handles token refresh + retry once on 401.
     *
     * @param pathOrUrl e.g. {@code "/me/playlists?limit=50"} or the full
     *                  "next" URL from a paginated response
     */
    public static String get(String pathOrUrl) throws Exception {
        String access = TokenStore.load()
                .orElseThrow(() -> new IllegalStateException("Not logged in to Spotify"))
                .accessToken();

        HttpResponse<String> resp = doGet(pathOrUrl, access);

        // Access token might have expired mid-flight — refresh and retry.
        if (resp.statusCode() == 401) {
            String fresh = SpotifyAuth.refresh();
            resp = doGet(pathOrUrl, fresh);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Spotify " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private static HttpResponse<String> doGet(String pathOrUrl, String accessToken) throws Exception {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : BASE + pathOrUrl;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }
}