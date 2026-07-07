package com.trackoff.io.spotify;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Ephemeral loopback web server used for the OAuth redirect step.
 *
 * Spotify's browser page needs somewhere to send the {@code code} and
 * {@code state} query params. For a desktop app that "somewhere" is a
 * tiny server bound to localhost. We try a short list of ports —
 * the same three the user pre-registered in their Spotify dev app —
 * and use whichever is available.
 *
 * The server responds with a friendly HTML page ("you can close this
 * tab"), fires the {@link CompletableFuture} with the received code,
 * then shuts itself down. Total lifetime: usually under a second.
 */
final class OAuthCallbackServer implements AutoCloseable {

    /** Fallback list — must match what the user registered in Spotify. */
    static final int[] PORT_CANDIDATES = { 47821, 47822, 47823 };

    private final HttpServer server;
    private final int port;
    private final String expectedState;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();

    /** Bind to the first available port; throws if all are in use. */
    OAuthCallbackServer(String expectedState) throws IOException {
        this.expectedState = expectedState;

        HttpServer bound = null;
        int chosenPort = -1;
        IOException last = null;

        for (int p : PORT_CANDIDATES) {
            try {
                bound = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
                chosenPort = p;
                break;
            } catch (BindException e) {
                last = e;
            }
        }
        if (bound == null) {
            throw new IOException(
                    "None of the OAuth ports " + java.util.Arrays.toString(PORT_CANDIDATES)
                            + " were available. Close whatever's using them and try again.",
                    last);
        }

        this.server = bound;
        this.port = chosenPort;
        server.createContext("/callback", this::handleCallback);
        server.setExecutor(null);   // default executor is fine for one request
        server.start();
    }

    /** The redirect URI the user must have registered with Spotify. */
    String redirectUri() {
        return "http://127.0.0.1:" + port + "/callback";
    }

    /** Completes with the received {@code code}, or exceptionally on error. */
    CompletableFuture<String> awaitCode() {
        return codeFuture;
    }

    // ------------------------------------------------------------------

    private void handleCallback(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI());

        String err = q.get("error");
        String code = q.get("code");
        String state = q.get("state");

        String body;
        if (err != null) {
            body = htmlPage("Authorization failed",
                    "Spotify returned an error: <code>" + escape(err) + "</code>. "
                            + "You can close this tab and try again.");
            codeFuture.completeExceptionally(new RuntimeException("Spotify error: " + err));
        } else if (code == null || state == null) {
            body = htmlPage("Something's off",
                    "The redirect didn't include a code. You can close this tab.");
            codeFuture.completeExceptionally(new RuntimeException("Missing code/state"));
        } else if (!expectedState.equals(state)) {
            body = htmlPage("State mismatch",
                    "The auth response didn't match the request. This can happen if "
                            + "you had another login window open. Close this tab and retry.");
            codeFuture.completeExceptionally(new RuntimeException("State mismatch"));
        } else {
            body = htmlPage("You're all set!",
                    "Trackoff has received your Spotify authorization. "
                            + "You can close this tab and return to the app.");
            codeFuture.complete(code);
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = urlDecode(pair.substring(0, eq));
            String v = urlDecode(pair.substring(eq + 1));
            out.put(k, v);
        }
        return out;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A little Spotify-green landing page for the browser. */
    private static String htmlPage(String heading, String message) {
        return """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>Trackoff</title>
                <style>
                  html,body { height:100%%; margin:0; }
                  body { background:#121212; color:#e8e8e8; font-family: -apple-system,
                         Segoe UI, Roboto, sans-serif; display:flex; align-items:center;
                         justify-content:center; }
                  .card { background:#1e1e1e; padding:36px 42px; border-radius:14px;
                         box-shadow:0 12px 40px rgba(0,0,0,.5); max-width:520px; text-align:center; }
                  h1 { color:#1db954; margin:0 0 12px; font-size:26px; }
                  p  { color:#c0c0c0; line-height:1.5; margin:0; }
                </style></head>
                <body><div class="card"><h1>%s</h1><p>%s</p></div></body></html>
                """.formatted(heading, message);
    }
}