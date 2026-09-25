package com.lld.games.snakeandladder.dice;

/**
 * Strategy for producing a move value. Programming to this interface lets the game use
 * real random dice in production and a deterministic sequence in tests.
 */
public interface Dice {

    /** Rolls and returns the total face value. */
    int roll();
}
