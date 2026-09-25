package com.lld.games.snakeandladder.model;

/**
 * Anything on the board that teleports a player from {@code start} to {@code end}.
 * Snake and Ladder differ only in direction, so they share this abstraction.
 * New entity types (e.g. a "Portal") can be added without touching Board or Game (Open/Closed).
 */
public abstract class BoardEntity {

    private final int start;
    private final int end;

    protected BoardEntity(int start, int end) {
        if (start == end) {
            throw new IllegalArgumentException("Start and end cannot be the same: " + start);
        }
        this.start = start;
        this.end = end;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    /** Human-readable name used in logs/events, e.g. "Snake" or "Ladder". */
    public abstract String getType();

    @Override
    public String toString() {
        return getType() + "(" + start + " -> " + end + ")";
    }
}
