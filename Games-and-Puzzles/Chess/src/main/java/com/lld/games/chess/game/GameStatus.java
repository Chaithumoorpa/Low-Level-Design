package com.lld.games.chess.game;

import com.lld.games.chess.model.Color;

/** Overall result. Why the game ended is kept separately in {@link EndReason}. */
public enum GameStatus {
    ACTIVE,
    WHITE_WON,
    BLACK_WON,
    DRAW;

    public boolean isOver() {
        return this != ACTIVE;
    }

    public static GameStatus winFor(Color color) {
        return color == Color.WHITE ? WHITE_WON : BLACK_WON;
    }
}
