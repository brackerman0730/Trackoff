package com.rankify.model;

import java.util.Objects;

/**
 * Immutable representation of a single track.
 *
 * Required: id, title, artist. Everything else is optional metadata that
 * may be missing depending on the source (CSV export vs. Spotify API).
 */
public final class Song {

    private final String id;
    private final String title;
    private final String artist;
    private final String album;
    private final String albumDate;
    private final int    durationSeconds;
    private final int    bpm;
    private final int    popularity;
    private final String key;
    private final String camelot;
    private final int    energy;
    private final String genres;
    private final boolean explicit;

    /** URL to an album cover image (Spotify only). Empty if unavailable. */
    private final String imageUrl;

    /** URL to a 30-second MP3 preview (Spotify only). Empty if unavailable. */
    private final String previewUrl;

    private Song(Builder b) {
        this.id              = Objects.requireNonNull(b.id,     "id");
        this.title           = Objects.requireNonNull(b.title,  "title");
        this.artist          = Objects.requireNonNull(b.artist, "artist");
        this.album           = nullSafe(b.album);
        this.albumDate       = nullSafe(b.albumDate);
        this.durationSeconds = b.durationSeconds;
        this.bpm             = b.bpm;
        this.popularity      = b.popularity;
        this.key             = nullSafe(b.key);
        this.camelot         = nullSafe(b.camelot);
        this.energy          = b.energy;
        this.genres          = nullSafe(b.genres);
        this.explicit        = b.explicit;
        this.imageUrl        = nullSafe(b.imageUrl);
        this.previewUrl      = nullSafe(b.previewUrl);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    // ----- accessors -----
    public String  id()              { return id; }
    public String  title()           { return title; }
    public String  artist()          { return artist; }
    public String  album()           { return album; }
    public String  albumDate()       { return albumDate; }
    public int     durationSeconds() { return durationSeconds; }
    public int     bpm()             { return bpm; }
    public int     popularity()      { return popularity; }
    public String  key()             { return key; }
    public String  camelot()         { return camelot; }
    public int     energy()          { return energy; }
    public String  genres()          { return genres; }
    public boolean explicit()        { return explicit; }
    public String  imageUrl()        { return imageUrl; }
    public String  previewUrl()      { return previewUrl; }

    public boolean hasImage()   { return !imageUrl.isEmpty(); }
    public boolean hasPreview() { return !previewUrl.isEmpty(); }

    public String formattedDuration() {
        int m = durationSeconds / 60;
        int s = durationSeconds % 60;
        return String.format("%d:%02d", m, s);
    }

    @Override public boolean equals(Object o) { return o instanceof Song s && s.id.equals(this.id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return title + " — " + artist; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id, title, artist, album, albumDate, key, camelot, genres, imageUrl, previewUrl;
        private int durationSeconds, bpm, popularity, energy;
        private boolean explicit;

        public Builder id(String v)             { this.id = v;              return this; }
        public Builder title(String v)          { this.title = v;           return this; }
        public Builder artist(String v)         { this.artist = v;          return this; }
        public Builder album(String v)          { this.album = v;           return this; }
        public Builder albumDate(String v)      { this.albumDate = v;       return this; }
        public Builder durationSeconds(int v)   { this.durationSeconds = v; return this; }
        public Builder bpm(int v)               { this.bpm = v;             return this; }
        public Builder popularity(int v)        { this.popularity = v;      return this; }
        public Builder key(String v)            { this.key = v;             return this; }
        public Builder camelot(String v)        { this.camelot = v;         return this; }
        public Builder energy(int v)            { this.energy = v;          return this; }
        public Builder genres(String v)         { this.genres = v;          return this; }
        public Builder explicit(boolean v)      { this.explicit = v;        return this; }
        public Builder imageUrl(String v)       { this.imageUrl = v;        return this; }
        public Builder previewUrl(String v)     { this.previewUrl = v;      return this; }

        public Song build() { return new Song(this); }
    }
}