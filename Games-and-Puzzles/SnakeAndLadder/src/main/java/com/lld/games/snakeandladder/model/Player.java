package com.lld.games.snakeandladder.model;

import java.util.Objects;

/**
 * A participant in the game. Holds only identity and current position;
 * movement rules live in {@code Game} and {@code Board}.
 */
public class Player {

    private final String name;
    private int position;

    public Player(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        this.name = name;
        this.position = 0; // 0 = off the board, waiting to enter
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Player other)) return false;
        return name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name + "@" + position;
    }
}
