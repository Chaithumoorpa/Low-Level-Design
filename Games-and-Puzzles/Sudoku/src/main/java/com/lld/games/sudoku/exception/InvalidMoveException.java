package com.lld.games.sudoku.exception;

/** Thrown for moves that break the rules: off the board, on a given clue, bad value, or a conflict. */
public class InvalidMoveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidMoveException(String message) {
        super(message);
    }
}
