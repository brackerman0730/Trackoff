package com.trackoffios.deezer;

import com.trackoffios.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from the desktop app's DeezerPreviewSource — Deezer's public
 * search API needs no key/auth and returns a direct 30s preview MP3 URL,
 * which is what makes it usable here (Spotify's own preview_url field
 * is empty for most apps since Nov 2024). A plain relevance-ranked
 * query (not the field-qualified artist:"" track:"" syntax) is used
 * because live testing found the qualified form 0-results on legitimate
 * tracks (e.g. it misses Daft Punk "One More Time" entirely).
 */
public final class DeezerPreviewSource {

    private static final String BASE = "https://api.deezer.com/search";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private DeezerPreviewSource() {}

    /** Cached wrapper — "" cache value means "looked up, nothing found". */
    public static String resolve(String artist, String title) {
        String key = (artist + " " + title).toLowerCase();
        String cached = CACHE.get(key);
        if (cached != null) return cached;

        String found = "";
        try {
            found = findPreviewUrl(artist, title).orElse("");
        } catch (Exception ignored) {
            // Best-effort — a lookup failure just means no preview.
        }
        CACHE.put(key, found);
        return found;
    }

    private static Optional<String> findPreviewUrl(String artist, String title) throws IOException {
        String query = artist + " " + title;
        String url = BASE + "?limit=1&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build();
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
        String preview = Json.string(resp.body(), "preview");
        return preview.isEmpty() ? Optional.empty() : Optional.of(preview);
    }
}
