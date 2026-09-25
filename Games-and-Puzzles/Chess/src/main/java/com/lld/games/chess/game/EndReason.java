package com.lld.games.chess.game;

/** How a finished game ended. */
public enum EndReason {
    CHECKMATE,
    RESIGNATION,
    STALEMATE,
    FIFTY_MOVE_RULE,
    THREEFOLD_REPETITION,
    INSUFFICIENT_MATERIAL,
    DRAW_AGREED
}
