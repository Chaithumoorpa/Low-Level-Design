package com.lld.games.minesweeper.game;

/**
 * NOT_STARTED -> IN_PROGRESS (first reveal places the mines) -> WON | LOST.
 * WON and LOST are terminal.
 */
public enum GameStatus {
    NOT_STARTED,
    IN_PROGRESS,
    WON,
    LOST;

    public boolean isOver() {
        return this == WON || this == LOST;
    }
}
