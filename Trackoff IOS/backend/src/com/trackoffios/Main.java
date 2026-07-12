package com.trackoffios;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.trackoffios.deezer.DeezerPreviewSource;
import com.trackoffios.lastfm.LastFmClient;
import com.trackoffios.lastfm.LastFmPlaycountLookup;
import com.trackoffios.spotify.SpotifyAuth;
import com.trackoffios.spotify.SpotifyPlaylists;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Trackoff iOS — a mobile-web companion to the Trackoff desktop app.
 * Single self-contained Java process: serves the static frontend AND
 * proxies Spotify/Last.fm/Deezer (so the phone browser never needs to
 * make a cross-origin call, and OAuth secrets never reach the client).
 */
public final class Main {

    private static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        Path frontendDir = resolveFrontendDir();
        System.out.println("[Trackoff iOS] Serving frontend from: " + frontendDir.toAbsolutePath());

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        // ---- Static frontend ----
        server.createContext("/", ex -> StaticFiles.serve(ex, frontendDir));

        // ---- App status / settings ----
        server.createContext("/api/status", safe(Main::handleStatus));
        server.createContext("/api/settings/spotify", safe(Main::handleSetSpotifyCreds));
        server.createContext("/api/settings/spotify/disconnect", safe(Main::handleSpotifyDisconnect));
        server.createContext("/api/settings/lastfm", safe(Main::handleSetLastFmCreds));
        server.createContext("/api/settings/lastfm/disconnect", safe(Main::handleLastFmDisconnect));

        // ---- Spotify OAuth ----
        server.createContext("/api/spotify/login", safe(Main::handleSpotifyLogin));
        server.createContext("/api/spotify/callback", safe(Main::handleSpotifyCallback));

        // ---- Library ----
        server.createContext("/api/library/playlists", safe(Main::handlePlaylists));
        server.createContext("/api/library/playlist", safe(Main::handlePlaylistTracks));
        server.createContext("/api/csv/parse", safe(Main::handleCsvParse));

        // ---- Preview / play counts ----
        server.createContext("/api/preview", safe(Main::handlePreview));
        server.createContext("/api/lastfm/playcount", safe(Main::handlePlaycount));
        server.createContext("/api/lastfm/override", safe(Main::handleSetOverride));
        server.createContext("/api/lastfm/override/clear", safe(Main::handleClearOverride));

        server.start();
        System.out.println("[Trackoff iOS] Listening on http://0.0.0.0:" + PORT
                + "  (open http://localhost:" + PORT + " on this machine)");
    }

    // ==================================================================
    //  Status / settings
    // ==================================================================

    private static void handleStatus(HttpExchange ex) throws IOException {
        String clientId = SettingsStore.get("spotify.client_id", "");
        String lastfmUser = SettingsStore.get("lastfm.username", "");
        String json = "{"
                + Json.field("spotifyConfigured", !clientId.isEmpty()) + ","
                + Json.field("spotifyLinked", SpotifyAuth.isLinked()) + ","
                + Json.field("lastfmLinked", LastFmClient.isLinked()) + ","
                + Json.field("lastfmUsername", lastfmUser)
                + "}";
        Req.sendJson(ex, 200, json);
    }

    private static void handleSetSpotifyCreds(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { Req.sendError(ex, 405, "POST only"); return; }
        String body = Req.body(ex);
        String clientId = Json.string(body, "clientId");
        String clientSecret = Json.string(body, "clientSecret");
        if (clientId.isBlank() || clientSecret.isBlank()) { Req.sendError(ex, 400, "clientId and clientSecret required"); return; }
        SettingsStore.set("spotify.client_id", clientId);
        SettingsStore.set("spotify.client_secret", clientSecret);
        Req.sendJson(ex, 200, "{" + Json.field("ok", true) + "}");
    }

    private static void handleSpotifyDisconnect(HttpExchange ex) throws IOException {
        SpotifyAuth.logout();
        Req.sendJson(ex, 200, "{" + Json.field("ok", true) + "}");
    }

    private static void handleSetLastFmCreds(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { Req.sendError(ex, 405, "POST only"); return; }
        String body = Req.body(ex);
        String username = Json.string(body, "username");
        String apiKey = Json.string(body, "apiKey");
        try {
            LastFmClient.UserInfo info = LastFmClient.fetchUserInfo(username, apiKey);
            LastFmClient.saveCredentials(info.username(), apiKey, info.playcount());
            Req.sendJson(ex, 200, "{" + Json.field("ok", true) + "," + Json.field("playcount", info.playcount()) + "}");
        } catch (Exception e) {
            Req.sendError(ex, 400, e.getMessage());
        }
    }

    private static void handleLastFmDisconnect(HttpExchange ex) throws IOException {
        LastFmClient.clearCredentials();
        Req.sendJson(ex, 200, "{" + Json.field("ok", true) + "}");
    }

    // ==================================================================
    //  Spotify OAuth
    // ==================================================================

    private static void handleSpotifyLogin(HttpExchange ex) throws IOException {
        try {
            String redirectUri = Req.baseUrl(ex) + "/api/spotify/callback";
            String authorizeUrl = SpotifyAuth.buildAuthorizeUrl(redirectUri);
            Req.redirect(ex, authorizeUrl);
        } catch (Exception e) {
            Req.sendError(ex, 400, e.getMessage());
        }
    }

    private static void handleSpotifyCallback(HttpExchange ex) throws IOException {
        Map<String, String> q = Req.queryParams(ex);
        String code = q.get("code");
        String state = q.get("state");
        String error = q.get("error");
        if (error != null) { Req.redirect(ex, "/?spotifyError=" + error); return; }
        try {
            String redirectUri = Req.baseUrl(ex) + "/api/spotify/callback";
            SpotifyAuth.handleCallback(code, state, redirectUri);
            Req.redirect(ex, "/?spotifyConnected=1");
        } catch (Exception e) {
            Req.redirect(ex, "/?spotifyError=" + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // ==================================================================
    //  Library
    // ==================================================================

    private static void handlePlaylists(HttpExchange ex) throws IOException {
        try {
            Req.sendJson(ex, 200, SpotifyPlaylists.listMyPlaylists());
        } catch (Exception e) {
            Req.sendError(ex, 500, e.getMessage());
        }
    }

    private static void handlePlaylistTracks(HttpExchange ex) throws IOException {
        String id = Req.queryParams(ex).get("id");
        if (id == null) { Req.sendError(ex, 400, "id required"); return; }
        try {
            Req.sendJson(ex, 200, SpotifyPlaylists.loadPlaylistTracks(id));
        } catch (Exception e) {
            Req.sendError(ex, 500, e.getMessage());
        }
    }

    private static void handleCsvParse(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { Req.sendError(ex, 405, "POST only"); return; }
        String csvText = Req.body(ex);
        Req.sendJson(ex, 200, CsvParser.parseToJson(csvText));
    }

    // ==================================================================
    //  Preview / play counts
    // ==================================================================

    private static void handlePreview(HttpExchange ex) throws IOException {
        Map<String, String> q = Req.queryParams(ex);
        String artist = q.getOrDefault("artist", "");
        String title = q.getOrDefault("title", "");
        String url = DeezerPreviewSource.resolve(artist, title);
        Req.sendJson(ex, 200, "{" + Json.field("url", url.isEmpty() ? null : url) + "}");
    }

    private static void handlePlaycount(HttpExchange ex) throws IOException {
        Map<String, String> q = Req.queryParams(ex);
        String artist = q.getOrDefault("artist", "");
        String title = q.getOrDefault("title", "");
        String songId = q.getOrDefault("songId", artist + "|" + title);
        long plays = LastFmPlaycountLookup.resolve(songId, artist, title);
        boolean manual = LastFmPlaycountLookup.readOverride(songId).isPresent();
        Req.sendJson(ex, 200, "{" + Json.field("plays", plays) + "," + Json.field("manual", manual) + "}");
    }

    private static void handleSetOverride(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { Req.sendError(ex, 405, "POST only"); return; }
        String body = Req.body(ex);
        String songId = Json.string(body, "songId");
        String url = Json.string(body, "url");
        var match = LastFmClient.parseTrackUrl(url);
        if (match.isEmpty()) { Req.sendError(ex, 400, "Couldn't read a track from that URL"); return; }
        LastFmPlaycountLookup.setOverride(songId, match.get().artist(), match.get().name());
        Req.sendJson(ex, 200, "{" + Json.field("ok", true) + "}");
    }

    private static void handleClearOverride(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { Req.sendError(ex, 405, "POST only"); return; }
        String songId = Json.string(Req.body(ex), "songId");
        LastFmPlaycountLookup.clearOverride(songId);
        Req.sendJson(ex, 200, "{" + Json.field("ok", true) + "}");
    }

    // ==================================================================

    private static Path resolveFrontendDir() {
        // Works whether launched from backend/ (dev) or wherever run.ps1 cd's to.
        Path[] candidates = {
                Paths.get("..", "frontend"),
                Paths.get("frontend"),
                Paths.get("Trackoff IOS", "frontend"),
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p;
        }
        return Paths.get("frontend"); // fall back; StaticFiles will 404 usefully
    }

    /**
     * Every route is wrapped in this. A handler throwing an unexpected
     * (non-IOException) error — e.g. a stale-class NoSuchMethodError seen
     * during development after editing code without restarting the
     * server — otherwise leaves HttpExchange never closed, which hangs
     * the client's fetch() forever instead of surfacing a clear error.
     */
    private static HttpHandler safe(HttpHandler handler) {
        return ex -> {
            try {
                handler.handle(ex);
            } catch (Exception | Error e) {
                System.err.println("[Trackoff iOS] Unhandled error in " + ex.getRequestURI() + ": " + e);
                try {
                    Req.sendError(ex, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
                } catch (Exception closeFailed) {
                    // Response may already be committed — nothing more we can do.
                }
            }
        };
    }
}
