package com.lld.games.chess.model;

/**
 * A square on the board. {@code file} 0..7 = a..h, {@code rank} 0..7 = 1..8.
 * Immutable value object, safe as a map key.
 */
public record Position(int file, int rank) {

    public Position {
        if (!inBounds(file, rank)) {
            throw new IllegalArgumentException("Square off the board: file=" + file + ", rank=" + rank);
        }
    }

    public static boolean inBounds(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    /** Parses algebraic notation, e.g. "e4". */
    public static Position of(String square) {
        if (square == null || square.length() != 2) {
            throw new IllegalArgumentException("Expected a square like e4, got: " + square);
        }
        int file = Character.toLowerCase(square.charAt(0)) - 'a';
        int rank = square.charAt(1) - '1';
        if (!inBounds(file, rank)) {
            throw new IllegalArgumentException("Square off the board: " + square);
        }
        return new Position(file, rank);
    }

    /** Light squares are those where file + rank is odd (a1 is dark). */
    public boolean isLightSquare() {
        return (file + rank) % 2 == 1;
    }

    @Override
    public String toString() {
        return "" + (char) ('a' + file) + (char) ('1' + rank);
    }
}
