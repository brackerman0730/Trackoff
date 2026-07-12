package com.trackoffios;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small request-handling helpers shared by every API route. */
public final class Req {

    private Req() {}

    public static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) return out;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(k), decode(v));
        }
        return out;
    }

    public static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    /** Best guess at the base URL (scheme://host[:port]) this request arrived on — used to build OAuth redirect URIs that work whether accessed via localhost or a LAN IP. */
    public static String baseUrl(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) host = "localhost:" + ex.getLocalAddress().getPort();
        return "http://" + host;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    public static void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, "{" + Json.field("error", message) + "}");
    }

    public static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
}
