package com.lld.social.network.model;

import java.time.Instant;

/** PENDING → ACCEPTED / DECLINED (by the receiver) / CANCELLED (by the sender, or by a block). */
public final class FriendRequest {

    public enum Status {
        PENDING, ACCEPTED, DECLINED, CANCELLED
    }

    private final String id;
    private final String fromId;
    private final String toId;
    private final Instant sentAt;
    private Status status = Status.PENDING;

    public FriendRequest(String id, String fromId, String toId, Instant sentAt) {
        this.id = id;
        this.fromId = fromId;
        this.toId = toId;
        this.sentAt = sentAt;
    }

    public String id() {
        return id;
    }

    public String fromId() {
        return fromId;
    }

    public String toId() {
        return toId;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return id + " " + fromId + " -> " + toId + " [" + status + "]";
    }
}
