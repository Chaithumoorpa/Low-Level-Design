package com.lld.games.snakeandladder.exception;

/** Thrown when snakes/ladders are placed in a way that breaks the board's invariants. */
public class InvalidBoardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidBoardException(String message) {
        super(message);
    }
}
