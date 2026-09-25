package com.lld.games.minesweeper.exception;

/** Thrown for moves that break the rules: off the board, or after the game has ended. */
public class InvalidMoveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidMoveException(String message) {
        super(message);
    }
}
