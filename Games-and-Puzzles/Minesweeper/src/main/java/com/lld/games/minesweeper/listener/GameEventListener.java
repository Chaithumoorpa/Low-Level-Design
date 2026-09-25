package com.lld.games.minesweeper.listener;

import com.lld.games.minesweeper.model.Position;

import java.util.List;

/**
 * Observer pattern: sound effects, timers, analytics or a web UI can react to the game
 * without the Game depending on any of them.
 */
public interface GameEventListener {

    default void onCellsRevealed(List<Position> positions) {
    }

    default void onFlagToggled(Position position, boolean flagged) {
    }

    default void onGameWon() {
    }

    default void onGameLost(Position explodedAt) {
    }
}
