package com.lld.games.sudoku.listener;

import com.lld.games.sudoku.model.Position;

/** Observer pattern: a UI, timer or stats tracker can follow the game from outside. */
public interface GameEventListener {

    /** A cell's value changed (by a move, a hint, an undo or a redo). */
    default void onCellChanged(Position position, int oldValue, int newValue) {
    }

    default void onSolved(int movesMade, int hintsUsed) {
    }
}
