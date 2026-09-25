package com.lld.games.snakeandladder.dice;

/**
 * Deterministic dice that replays a fixed sequence of values (cycling when exhausted).
 * Used for unit tests and scripted demos where the outcome must be predictable.
 */
public class FixedSequenceDice implements Dice {

    private final int[] values;
    private int index;

    public FixedSequenceDice(int... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("Provide at least one value");
        }
        this.values = values.clone();
    }

    @Override
    public int roll() {
        int value = values[index];
        index = (index + 1) % values.length;
        return value;
    }
}
