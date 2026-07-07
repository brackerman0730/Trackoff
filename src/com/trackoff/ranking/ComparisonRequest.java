package com.trackoff.ranking;

import com.trackoff.model.Song;

/** A single "rank A vs B" question presented to the user. */
public record ComparisonRequest(Song left, Song right) { }