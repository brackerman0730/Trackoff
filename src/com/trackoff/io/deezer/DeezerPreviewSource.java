package com.trackoff.io.deezer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up 30-second MP3 preview clips from Deezer's public search API.
 *
 * Deezer's search endpoint needs no API key/auth and matches tracks by
 * artist + title, unlike Spotify's Web API preview_url field (which
 * Spotify stopped reliably populating for most apps in late 2024).
 *
 * Same hand-rolled style as {@code SpotifyApi}/{@code SpotifyPlaylistSource}
 * — plain java.net.http, regex JSON extraction, zero third-party deps.
 */
public final class DeezerPreviewSource {

    private static final String BASE = "https://api.deezer.com/search";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private DeezerPreviewSource() {}

    /**
     * Best-effort lookup: returns the preview MP3 URL for the closest
     * matching track, or empty if nothing was found (or the request
     * failed). Never throws for "no results" — only for genuine I/O
     * failure.
     */
    public static Optional<String> findPreviewUrl(String artist, String title) throws IOException {
        // A plain "artist title" query, ranked by Deezer's own relevance
        // scoring, finds far more real matches than the strict
        // artist:"..." track:"..." field-qualified syntax, which 0-results
        // on anything that isn't an exact string match (e.g. it misses
        // Daft Punk "One More Time" entirely despite it being on Deezer).
        String query = artist + " " + title;
        String url = BASE + "?limit=1&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Deezer lookup interrupted", e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Deezer " + resp.statusCode() + ": " + resp.body());
        }

        String preview = extractString(resp.body(), "preview");
        return preview.isEmpty() ? Optional.empty() : Optional.of(preview);
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                           .matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
