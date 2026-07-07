package com.rankify.ranking;

import com.rankify.model.Song;

/** A single "rank A vs B" question presented to the user. */
public record ComparisonRequest(Song left, Song right) { }