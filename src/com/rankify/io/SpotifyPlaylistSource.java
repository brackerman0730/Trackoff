package com.rankify.io;

import com.rankify.model.Playlist;
import com.rankify.model.Song;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a playlist directly from the Spotify Web API using the
 * Client Credentials flow (no user login required — works for any
 * public playlist).
 *
 * Endpoints used:
 *   POST https://accounts.spotify.com/api/token     (auth)
 *   GET  https://api.spotify.com/v1/playlists/{id}  (metadata)
 *   GET  https://api.spotify.com/v1/playlists/{id}/tracks?offset=...
 *
 * We do NOT use any third-party JSON library — the responses are parsed
 * with a small hand-written extractor that's good enough for the fields
 * we care about (id, name, artists, album, duration, popularity, etc.).
 */
public final class SpotifyPlaylistSource implements PlaylistSource {

    private static final String TOKEN_URL    = "https://accounts.spotify.com/api/token";
    private static final String PLAYLIST_URL = "https://api.spotify.com/v1/playlists/";

    private final String clientId;
    private final String clientSecret;
    private final HttpClient http = HttpClient.newHttpClient();

    public SpotifyPlaylistSource(String clientId, String clientSecret) {
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public Playlist load(String playlistUrlOrId) throws IOException {
        String playlistId = extractPlaylistId(playlistUrlOrId);
        try {
            String token = fetchAccessToken();
            String name  = fetchPlaylistName(playlistId, token);
            List<Song> songs = fetchAllTracks(playlistId, token);
            return new Playlist(name, songs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting Spotify", e);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Accepts any of:
     *   https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=...
     *   spotify:playlist:37i9dQZF1DXcBWIGoYBM5M
     *   37i9dQZF1DXcBWIGoYBM5M
     */
    private String extractPlaylistId(String input) {
        String s = input.trim();
        Matcher m = Pattern.compile("playlist[:/]([A-Za-z0-9]+)").matcher(s);
        if (m.find()) return m.group(1);
        return s;
    }

    private String fetchAccessToken() throws IOException, InterruptedException {
        String creds = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + creds)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Auth failed (" + res.statusCode() + "): " + res.body());
        }
        return extractString(res.body(), "access_token");
    }

    private String fetchPlaylistName(String id, String token) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(PLAYLIST_URL + id + "?fields=name"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Playlist lookup failed (" + res.statusCode() + "): " + res.body());
        }
        String name = extractString(res.body(), "name");
        return name.isEmpty() ? "Spotify Playlist" : name;
    }

    private List<Song> fetchAllTracks(String id, String token)
            throws IOException, InterruptedException {

        List<Song> all = new ArrayList<>();
        int offset = 0;
        final int pageSize = 100;

        while (true) {
            String url = PLAYLIST_URL + id + "/tracks"
                    + "?limit=" + pageSize
                    + "&offset=" + offset
                    + "&fields=" + URLEncoder.encode(
                        "items(track(id,name,duration_ms,popularity,explicit,preview_url," +
                        "artists(name),album(name,release_date,images))),next,total",
                        StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IOException("Track fetch failed (" + res.statusCode() + "): " + res.body());
            }

            List<Song> page = parseTracks(res.body());
            all.addAll(page);

            if (page.size() < pageSize) break;
            offset += pageSize;
        }
        return all;
    }

    // ------------------------------------------------------------------
    //  Tiny hand-rolled JSON extractor.
    // ------------------------------------------------------------------

    private List<Song> parseTracks(String json) {
        List<Song> songs = new ArrayList<>();

        int cursor = 0;
        while (true) {
            int trackStart = json.indexOf("\"track\":{", cursor);
            if (trackStart < 0) break;
            int braceStart = trackStart + "\"track\":".length();
            int braceEnd   = matchBrace(json, braceStart);
            if (braceEnd < 0) break;

            String trackJson = json.substring(braceStart, braceEnd + 1);
            cursor = braceEnd + 1;

            Song song = parseSingleTrack(trackJson);
            if (song != null) songs.add(song);
        }
        return songs;
    }

private Song parseSingleTrack(String t) {
        String id = extractString(t, "id");
        if (id.isEmpty() || id.equals("null")) return null;

        String title    = extractString(t, "name");
        int durationMs  = extractInt(t, "duration_ms");
        int popularity  = extractInt(t, "popularity");
        boolean explicit = extractBool(t, "explicit");
        String previewUrl = extractString(t, "preview_url");   // may be ""

        // Artists → comma-separated list of names.
        String artistsBlock = extractArrayBlock(t, "artists");
        String artist = String.join(", ", extractAllStrings(artistsBlock, "name"));

        // Album metadata + first image URL (Spotify returns 640/300/64 sizes;
        // we grab the first, which is the largest).
        String albumBlock = extractObjectBlock(t, "album");
        String album      = extractString(albumBlock, "name");
        String albumDate  = extractString(albumBlock, "release_date");

        String imagesBlock = extractArrayBlock(albumBlock, "images");
        List<String> imageUrls = extractAllStrings(imagesBlock, "url");
        String imageUrl = imageUrls.isEmpty() ? "" : imageUrls.get(0);

        return Song.builder()
                .id(id)
                .title(title)
                .artist(artist.isEmpty() ? "Unknown" : artist)
                .album(album)
                .albumDate(albumDate)
                .durationSeconds(durationMs / 1000)
                .popularity(popularity)
                .explicit(explicit)
                .imageUrl(imageUrl)
                .previewUrl(previewUrl)
                .build();
    }

    // ----- JSON helpers -----

    private String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                           .matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
                           .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private boolean extractBool(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)")
                           .matcher(json);
        return m.find() && m.group(1).equals("true");
    }

    private List<String> extractAllStrings(String json, String key) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                           .matcher(json);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private String extractArrayBlock(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":[");
        if (idx < 0) return "";
        int start = json.indexOf('[', idx);
        int end   = matchBracket(json, start);
        return end < 0 ? "" : json.substring(start, end + 1);
    }

    private String extractObjectBlock(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":{");
        if (idx < 0) return "";
        int start = json.indexOf('{', idx);
        int end   = matchBrace(json, start);
        return end < 0 ? "" : json.substring(start, end + 1);
    }

    private int matchBrace(String s, int openIdx)   { return matchDelim(s, openIdx, '{', '}'); }
    private int matchBracket(String s, int openIdx) { return matchDelim(s, openIdx, '[', ']'); }

    private int matchDelim(String s, int openIdx, char open, char close) {
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\' && i + 1 < s.length()) { i++; continue; }
                if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == open)  depth++;
                else if (c == close) { depth--; if (depth == 0) return i; }
            }
        }
        return -1;
    }
}