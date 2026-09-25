package com.lld.games.chess.model;

/** A participant. Kept separate from Color so names, ratings or an AI flag can be added later. */
public record Player(String name, Color color) {

    public Player {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        if (color == null) {
            throw new IllegalArgumentException("Player color is required");
        }
    }
}
