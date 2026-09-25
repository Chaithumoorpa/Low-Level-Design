package com.lld.games.sudoku.game;

/** How the game treats a digit that clashes with its row, column or box. */
public enum ValidationMode {
    /** Refuse the move with an explanation (friendly for beginners). */
    STRICT,
    /** Accept it, but report conflicting cells; the puzzle only counts as solved without conflicts. */
    LENIENT
}
