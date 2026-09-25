package com.lld.games.snakeandladder.game;

/** Lifecycle of a game. Transitions only move forward: NOT_STARTED -> IN_PROGRESS -> FINISHED. */
public enum GameStatus {
    NOT_STARTED,
    IN_PROGRESS,
    FINISHED
}
