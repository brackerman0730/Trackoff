package com.trackoffios.spotify;

import com.trackoffios.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads playlists + tracks from Spotify's Web API and re-serializes them
 * as this server's own clean JSON shape (adapted from the desktop app's
 * SpotifyPlaylistSource + LibraryView's playlist-listing parsing).
 */
public final class SpotifyPlaylists {

    private SpotifyPlaylists() {}

    /** JSON array of {id, name, imageUrl, trackCount, ownerName, isOwned}. */
    public static String listMyPlaylists() throws Exception {
        String myId = Json.string(SpotifyApi.get("/me"), "id");
        List<String> items = new ArrayList<>();
        String next = "/me/playlists?limit=50";

        while (next != null) {
            String body = SpotifyApi.get(next);
            String arr = Json.arrayBlock(body, "items");
            String inner = arr.isEmpty() ? "" : arr.substring(1, arr.length() - 1);
            for (String obj : Json.splitObjects(inner)) {
                String id = Json.string(obj, "id");
                String name = Json.string(obj, "name");
                if (id.isEmpty() || name.isEmpty()) continue;
                String image = firstImageUrl(obj);
                int trackCount = Json.intVal(Json.objectBlock(obj, "tracks"), "total");

                String ownerBlock = Json.objectBlock(obj, "owner");
                String ownerId = Json.string(ownerBlock, "id");
                String ownerName = Json.string(ownerBlock, "display_name");
                boolean isOwned = !myId.isEmpty() && myId.equals(ownerId);

                items.add("{" + String.join(",",
                        Json.field("id", id),
                        Json.field("name", name),
                        Json.field("imageUrl", image),
                        Json.field("trackCount", trackCount),
                        Json.field("ownerName", ownerName.isEmpty() ? ownerId : ownerName),
                        Json.field("isOwned", isOwned)) + "}");
            }
            next = Json.stringOrNull(body, "next");
        }
        return "[" + String.join(",", items) + "]";
    }

    /** JSON array of {id, title, artist, album, imageUrl, previewUrl, durationMs, popularity, explicit}. */
    public static String loadPlaylistTracks(String playlistId) throws Exception {
        List<String> items = new ArrayList<>();
        String next = "/playlists/" + playlistId + "/items"
                + "?limit=100&fields=" + URLEncoder.encode(
                        "items(item(id,name,duration_ms,popularity,explicit,preview_url,"
                                + "artists(name),album(name,images))),next",
                        StandardCharsets.UTF_8);

        while (next != null) {
            String body = SpotifyApi.get(next);
            String arr = Json.arrayBlock(body, "items");
            String inner = arr.isEmpty() ? "" : arr.substring(1, arr.length() - 1);

            // Each element is {"item": {...}} — pull the inner "item" objects out by hand
            // since Json.splitObjects would otherwise treat the wrapper as the object.
            int cursor = 0;
            while (true) {
                int itemStart = inner.indexOf("\"item\":{", cursor);
                if (itemStart < 0) break;
                int braceStart = itemStart + "\"item\":".length();
                int braceEnd = Json.matchBrace(inner, braceStart);
                if (braceEnd < 0) break;
                String track = inner.substring(braceStart, braceEnd + 1);
                cursor = braceEnd + 1;

                String id = Json.string(track, "id");
                if (id.isEmpty()) continue;

                String albumBlock = Json.objectBlock(track, "album");
                String artistsBlock = Json.arrayBlock(track, "artists");
                String flat = track;
                if (!albumBlock.isEmpty()) flat = flat.replace(albumBlock, "");
                if (!artistsBlock.isEmpty()) flat = flat.replace(artistsBlock, "");

                String title = Json.string(flat, "name");
                String artist = String.join(", ", Json.allStrings(artistsBlock, "name"));
                String album = Json.string(albumBlock, "name");
                String image = firstImageUrl(track);
                String previewUrl = Json.string(track, "preview_url");
                int durationMs = Json.intVal(track, "duration_ms");
                int popularity = Json.intVal(track, "popularity");
                boolean explicit = Json.boolVal(track, "explicit");

                items.add("{" + String.join(",",
                        Json.field("id", id),
                        Json.field("title", title),
                        Json.field("artist", artist.isEmpty() ? "Unknown" : artist),
                        Json.field("album", album),
                        Json.field("imageUrl", image),
                        Json.field("previewUrl", previewUrl),
                        Json.field("durationMs", durationMs),
                        Json.field("popularity", popularity),
                        Json.field("explicit", explicit)) + "}");
            }
            next = Json.stringOrNull(body, "next");
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String firstImageUrl(String obj) {
        String arr = Json.arrayBlock(obj, "images");
        if (arr.isEmpty()) return "";
        List<String> urls = Json.allStrings(arr, "url");
        return urls.isEmpty() ? "" : urls.get(0);
    }
}
