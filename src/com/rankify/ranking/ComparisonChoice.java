package com.rankify.ranking;

/**
 * The possible answers a user can give when shown two songs.
 *
 *  LEFT           — left song preferred; cached for transitivity.
 *  RIGHT          — right song preferred; cached for transitivity.
 *  REMOVE_LEFT    — user doesn't know the left song; drop it from the ranking.
 *  REMOVE_RIGHT   — user doesn't know the right song; drop it from the ranking.
 *  REMOVE_BOTH    — user doesn't know either; drop both.
 *  SKIP_TIE       — user knows both but can't decide; resolved by popularity,
 *                   NOT cached (so weak preferences don't propagate).
 */
public enum ComparisonChoice {
    LEFT, RIGHT,
    REMOVE_LEFT, REMOVE_RIGHT, REMOVE_BOTH,
    SKIP_TIE
}