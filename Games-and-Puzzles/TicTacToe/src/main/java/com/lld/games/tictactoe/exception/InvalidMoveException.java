package com.lld.games.tictactoe.exception;

/** Thrown for moves off the board, onto an occupied cell, or after the game has ended. */
public class InvalidMoveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidMoveException(String message) {
        super(message);
    }
}
