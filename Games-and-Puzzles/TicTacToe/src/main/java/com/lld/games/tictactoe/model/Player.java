package com.lld.games.tictactoe.model;

/** A participant and the symbol they play. */
public record Player(String name, Symbol symbol) {

    public Player {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        if (symbol == null) {
            throw new IllegalArgumentException("Player symbol is required");
        }
    }

    @Override
    public String toString() {
        return name + " (" + symbol + ")";
    }
}
