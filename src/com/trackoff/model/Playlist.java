package com.trackoff.model;

import java.util.Collections;
import java.util.List;

/**
 * A named, ordered list of songs. Immutable wrapper used to pass
 * playlists around without exposing the underlying list.
 */
public final class Playlist {

    private final String name;
    private final List<Song> songs;

    public Playlist(String name, List<Song> songs) {
        this.name  = name;
        this.songs = List.copyOf(songs);
    }

    public String     name()  { return name; }
    public List<Song> songs() { return Collections.unmodifiableList(songs); }
    public int        size()  { return songs.size(); }
}