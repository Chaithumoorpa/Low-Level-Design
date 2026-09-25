package com.lld.games.snakeandladder.model;

/** A snake moves a player DOWN from its head to its tail. */
public class Snake extends BoardEntity {

    public Snake(int head, int tail) {
        super(head, tail);
        if (tail >= head) {
            throw new IllegalArgumentException(
                    "Snake tail (" + tail + ") must be below its head (" + head + ")");
        }
    }

    @Override
    public String getType() {
        return "Snake";
    }
}
