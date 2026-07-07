package com.rankify.io;

import com.rankify.model.Playlist;
import java.io.IOException;

/**
 * Abstraction over "where playlists come from". Today we load CSV exports
 * created by an external converter; tomorrow we'll plug a Spotify Web API
 * implementation in behind this same interface.
 */
public interface PlaylistSource {

    /** Load a playlist given some identifier (file path, URL, etc.). */
    Playlist load(String reference) throws IOException;
}