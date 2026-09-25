package com.lld.games.chess.exception;

/** Thrown when a requested move is not legal, is out of turn, or the game is already over. */
public class InvalidMoveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidMoveException(String message) {
        super(message);
    }
}
