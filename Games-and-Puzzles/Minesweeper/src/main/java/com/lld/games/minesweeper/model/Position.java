package com.lld.games.minesweeper.model;

/**
 * Immutable (row, col) coordinate. A record gives equals/hashCode for free,
 * so positions can be used safely as keys in sets and maps.
 */
public record Position(int row, int col) {

    public static Position of(int row, int col) {
        return new Position(row, col);
    }

    @Override
    public String toString() {
        return "(" + row + "," + col + ")";
    }
}
