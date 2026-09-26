package com.lld.messaging.chat.model;

/**
 * A user's place in a conversation. Receipts are two <b>pointers</b> ("delivered up to #n", "read up to
 * #m") instead of a flag per message: O(1) to update and enough to derive every tick.
 */
public final class Membership {

    public enum Role {
        ADMIN, MEMBER
    }

    private final String userId;
    private final long joinedAfterSeq;
    private Role role;
    private long deliveredUpTo;
    private long readUpTo;
    private boolean muted;

    public Membership(String userId, Role role, long joinedAfterSeq) {
        this.userId = userId;
        this.role = role;
        this.joinedAfterSeq = joinedAfterSeq;
        this.deliveredUpTo = joinedAfterSeq;
        this.readUpTo = joinedAfterSeq;
    }

    public String userId() {
        return userId;
    }

    public Role role() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    /** Messages up to this seq were sent before the user joined and are not visible to them. */
    public long joinedAfterSeq() {
        return joinedAfterSeq;
    }

    public boolean canSee(long seq) {
        return seq > joinedAfterSeq;
    }

    public long deliveredUpTo() {
        return deliveredUpTo;
    }

    public long readUpTo() {
        return readUpTo;
    }

    /** Pointers only move forward. @return true if something changed */
    public boolean advance(long delivered, long read) {
        long d = Math.max(deliveredUpTo, Math.max(delivered, read));
        long r = Math.max(readUpTo, read);
        boolean changed = d != deliveredUpTo || r != readUpTo;
        deliveredUpTo = d;
        readUpTo = r;
        return changed;
    }

    public boolean muted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }
}
