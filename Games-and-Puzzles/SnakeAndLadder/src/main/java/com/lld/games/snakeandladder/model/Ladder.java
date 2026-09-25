package com.lld.games.snakeandladder.model;

/** A ladder moves a player UP from its bottom to its top. */
public class Ladder extends BoardEntity {

    public Ladder(int bottom, int top) {
        super(bottom, top);
        if (top <= bottom) {
            throw new IllegalArgumentException(
                    "Ladder top (" + top + ") must be above its bottom (" + bottom + ")");
        }
    }

    @Override
    public String getType() {
        return "Ladder";
    }
}
