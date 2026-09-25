package com.lld.games.minesweeper.model;

/**
 * What the player can see on a cell.
 *
 * <pre>
 *   HIDDEN  --toggleFlag--> FLAGGED
 *   FLAGGED --toggleFlag--> HIDDEN
 *   HIDDEN  --reveal------> REVEALED   (terminal: a revealed cell never changes again)
 * </pre>
 */
public enum CellState {
    HIDDEN,
    FLAGGED,
    REVEALED
}
