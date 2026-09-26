package com.lld.social.qa.model;

/**
 * A member. Reputation is earned from other people's votes and unlocks {@link Privilege}s.
 * Stored as the running sum of reputation events; shown with a floor of 1, so a user is never
 * "negative" but every change stays exactly reversible (undoing a vote restores the sum).
 */
public final class User {

    private final String id;
    private final String name;
    private long earned;                       // sum of all reputation deltas

    public User(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public synchronized long reputation() {
        return Math.max(1, 1 + earned);
    }

    public synchronized void addReputation(long delta) {
        earned += delta;
    }

    public boolean can(Privilege p) {
        return reputation() >= p.minReputation();
    }

    @Override
    public String toString() {
        return name + " (" + reputation() + ")";
    }
}
