package com.trackoffios;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Serves the frontend/ directory as static files — no framework needed for this small a set of assets. */
public final class StaticFiles {

    private StaticFiles() {}

    public static void serve(HttpExchange ex, Path root) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";

        // Prevent escaping the frontend directory.
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) {
            byte[] body = "404 not found".getBytes();
            ex.sendResponseHeaders(404, body.length);
            ex.getResponseBody().write(body);
            ex.close();
            return;
        }

        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().add("Content-Type", contentType(file.toString()));
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css"))  return "text/css; charset=utf-8";
        if (lower.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }
}
