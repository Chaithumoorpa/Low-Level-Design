package com.lld.games.snakeandladder.dice;

import java.util.Random;

/**
 * One or more fair dice, each with {@code faces} sides. {@code roll()} returns the sum.
 * Covers the "configurable dice count" extension: {@code new StandardDice(2, 6)} rolls 2..12.
 */
public class StandardDice implements Dice {

    private final int count;
    private final int faces;
    private final Random random;

    public StandardDice() {
        this(1, 6);
    }

    public StandardDice(int count, int faces) {
        this(count, faces, new Random());
    }

    public StandardDice(int count, int faces, Random random) {
        if (count < 1) {
            throw new IllegalArgumentException("Need at least one die");
        }
        if (faces < 2) {
            throw new IllegalArgumentException("A die needs at least two faces");
        }
        this.count = count;
        this.faces = faces;
        this.random = random;
    }

    @Override
    public int roll() {
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += random.nextInt(faces) + 1;
        }
        return total;
    }

    public int getMinValue() {
        return count;
    }

    public int getMaxValue() {
        return count * faces;
    }
}
