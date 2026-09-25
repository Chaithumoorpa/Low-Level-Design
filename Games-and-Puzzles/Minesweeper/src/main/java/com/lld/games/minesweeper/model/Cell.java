package com.lld.games.minesweeper.model;

/**
 * One square on the board. Owns its own state transitions (hidden / flagged / revealed)
 * so the rules for "can I flag this?" live in exactly one place.
 */
public class Cell {

    private final Position position;
    private boolean mine;
    private int adjacentMines;
    private CellState state = CellState.HIDDEN;

    public Cell(Position position) {
        this.position = position;
    }

    public Position getPosition() {
        return position;
    }

    public boolean isMine() {
        return mine;
    }

    /** Setup step, called by the Board when mines are placed. */
    public void setMine(boolean mine) {
        this.mine = mine;
    }

    public int getAdjacentMines() {
        return adjacentMines;
    }

    public void setAdjacentMines(int adjacentMines) {
        this.adjacentMines = adjacentMines;
    }

    public CellState getState() {
        return state;
    }

    public boolean isHidden() {
        return state == CellState.HIDDEN;
    }

    public boolean isFlagged() {
        return state == CellState.FLAGGED;
    }

    public boolean isRevealed() {
        return state == CellState.REVEALED;
    }

    /** Reveals a hidden cell. Returns false if nothing changed (already revealed or flagged). */
    public boolean reveal() {
        if (state != CellState.HIDDEN) {
            return false;
        }
        state = CellState.REVEALED;
        return true;
    }

    /** Flags a hidden cell or un-flags a flagged one. Revealed cells cannot be flagged. */
    public boolean toggleFlag() {
        switch (state) {
            case HIDDEN -> state = CellState.FLAGGED;
            case FLAGGED -> state = CellState.HIDDEN;
            case REVEALED -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return position + (mine ? "*" : String.valueOf(adjacentMines)) + ":" + state;
    }
}
