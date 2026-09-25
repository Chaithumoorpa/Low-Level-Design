package com.lld.games.sudoku.model;

/** A cell coordinate, 0-based. Immutable and hashable. */
public record Position(int row, int col) {

    public static Position of(int row, int col) {
        return new Position(row, col);
    }

    /** 1-based, human-friendly form used in messages: "r5c3". */
    @Override
    public String toString() {
        return "r" + (row + 1) + "c" + (col + 1);
    }
}
