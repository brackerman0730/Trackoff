package com.trackoff.io;

import com.trackoff.io.spotify.SpotifyApi;
import com.trackoff.io.spotify.TokenStore;
import com.trackoff.model.Playlist;
import com.trackoff.model.Song;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a playlist straight from the Spotify Web API, using whatever
 * OAuth session the user already has set up on the "My Library" side
 * of the app.
 *
 * Endpoints used (both via SpotifyApi, which attaches the Bearer token
 * and transparently refreshes it on a 401):
 *   GET /playlists/{id}?fields=name,tracks.total
 *   GET /playlists/{id}/items?...   (follows Spotify's "next" link for
 *                                    pagination; also passes a market
 *                                    param when we can resolve one,
 *                                    since preview_url comes back more
 *                                    reliably with a market attached)
 *
 * Spotify only serves full playlist *contents* here for playlists the
 * logged-in user owns or collaborates on — someone else's playlist
 * (even public) 403s on this endpoint by design, not a bug on our end.
 *
 * Still zero third-party JSON deps — same hand-written extractor as
 * before.
 */
public final class SpotifyPlaylistSource implements PlaylistSource {

    public SpotifyPlaylistSource() {
    }

    @Override
    public Playlist load(String playlistUrlOrId) throws IOException {
        if (!TokenStore.isLinked()) {
            throw new IOException(
                    "Connect your Spotify account first (\"Connect Spotify account\" "
                  + "on the main menu), then try loading the URL again.");
        }

        String playlistId = extractPlaylistId(playlistUrlOrId);
        try {
            PlaylistMeta meta = fetchPlaylistMeta(playlistId);
            String market = resolveMarket();
            List<Song> songs = fetchAllTracks(playlistId, market);

            if (songs.isEmpty() && meta.declaredTrackCount() > 0) {
                throw new IOException(
                        "Spotify says \"" + meta.name() + "\" has " + meta.declaredTrackCount()
                      + " tracks, but returned none of them. Full track access only works "
                      + "for playlists you own or collaborate on — other people's playlists "
                      + "(even public ones) are metadata-only. Try one of your own playlists, "
                      + "or use My Library.");
            }

            return new Playlist(meta.name(), songs);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // SpotifyApi.get() throws a checked Exception for anything from
            // a bad HTTP status to a JSON hiccup — wrap it so PlaylistSource
            // callers only ever see IOException, same as before.
            throw new IOException("Spotify request failed: " + e.getMessage(), e);
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

    private record PlaylistMeta(String name, int declaredTrackCount) {}

    private PlaylistMeta fetchPlaylistMeta(String id) throws Exception {
        String body = SpotifyApi.get("/playlists/" + id + "?fields=name,tracks.total");
        String name = extractString(body, "name");

        String tracksBlock = extractObjectBlock(body, "tracks");
        int total = tracksBlock.isEmpty() ? 0 : extractInt(tracksBlock, "total");

        return new PlaylistMeta(name.isEmpty() ? "Spotify Playlist" : name, total);
    }

    /**
     * Best-effort: pull the logged-in user's market from /me so we can
     * pass it along on the tracks request (helps preview_url actually
     * come back populated). Returns null — meaning "just don't send a
     * market param" — if we can't resolve one for any reason, rather
     * than failing the whole load over it.
     *
     * Note: this needs the user-read-private scope. If SpotifyAuth's
     * SCOPES list doesn't include it, /me won't return "country" and
     * this quietly falls back to no market param.
     */
    private String resolveMarket() {
        try {
            String me = SpotifyApi.get("/me?fields=country");
            String country = extractString(me, "country");
            return country.isEmpty() ? null : country;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Song> fetchAllTracks(String id, String market) throws Exception {
        List<Song> all = new ArrayList<>();

        // fields (and market, once set) survive into the "next" link
        // Spotify hands back, so we only need to build this once — same
        // trick LibraryView relies on for /me/playlists pagination.
        StringBuilder next = new StringBuilder("/playlists/" + id + "/items"
                + "?limit=100"
                + "&fields=" + URLEncoder.encode(
                    "items(item(id,name,duration_ms,popularity,explicit,preview_url," +
                    "artists(name),album(name,release_date,images))),next,total",
                    StandardCharsets.UTF_8));
        if (market != null && !market.isEmpty()) {
            next.append("&market=").append(URLEncoder.encode(market, StandardCharsets.UTF_8));
        }

        String url = next.toString();
        while (url != null) {
            String body = SpotifyApi.get(url);
            all.addAll(parseTracks(body));
            url = extractNextUrlOrNull(body);
        }
        return all;
    }

    /** Spotify's "next" field is a full URL, or JSON null on the last page. */
    private String extractNextUrlOrNull(String json) {
        String next = extractString(json, "next");
        return next.isEmpty() ? null : next;
    }

    // ------------------------------------------------------------------
    //  Tiny hand-rolled JSON extractor.
    // ------------------------------------------------------------------

    private List<Song> parseTracks(String json) {
        List<Song> songs = new ArrayList<>();

        int cursor = 0;
        while (true) {
            int itemStart = json.indexOf("\"item\":{", cursor);
            if (itemStart < 0) break;
            int braceStart = itemStart + "\"item\":".length();
            int braceEnd   = matchBrace(json, braceStart);
            if (braceEnd < 0) break;

            String itemJson = json.substring(braceStart, braceEnd + 1);
            cursor = braceEnd + 1;

            Song song = parseSingleTrack(itemJson);
            if (song != null) songs.add(song);
        }
        return songs;
    }

    private Song parseSingleTrack(String t) {
        String id = extractString(t, "id");
        if (id.isEmpty() || id.equals("null")) return null;

        // Spotify returns object keys alphabetically, not in the order we
        // requested them — "album" and "artists" both land ahead of the
        // track's own top-level "name" and each has a nested "name" of its
        // own. Pull those nested blocks out first and strip them from the
        // text before hunting for the track title, or a plain "first name
        // we see" search grabs album.name by mistake (this was the "album
        // showing twice, no track name" bug).
        String albumBlock   = extractObjectBlock(t, "album");
        String artistsBlock = extractArrayBlock(t, "artists");

        String album     = extractString(albumBlock, "name");
        String albumDate = extractString(albumBlock, "release_date");
        String artist    = String.join(", ", extractAllStrings(artistsBlock, "name"));

        String flat = t;
        if (!albumBlock.isEmpty())   flat = flat.replace(albumBlock, "");
        if (!artistsBlock.isEmpty()) flat = flat.replace(artistsBlock, "");

        String title      = extractString(flat, "name");
        int durationMs    = extractInt(t, "duration_ms");
        int popularity    = extractInt(t, "popularity");
        boolean explicit  = extractBool(t, "explicit");
        String previewUrl = extractString(t, "preview_url");   // may be ""

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