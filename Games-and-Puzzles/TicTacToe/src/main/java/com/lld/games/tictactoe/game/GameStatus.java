package com.lld.games.tictactoe.game;

/**
 * IN_PROGRESS -> WON | DRAW. Who won is a separate field ({@code Game.getWinner()}), so adding a
 * third player or symbol never needs new enum constants like WINNER_Z.
 */
public enum GameStatus {
    IN_PROGRESS,
    WON,
    DRAW;

    public boolean isOver() {
        return this != IN_PROGRESS;
    }
}
