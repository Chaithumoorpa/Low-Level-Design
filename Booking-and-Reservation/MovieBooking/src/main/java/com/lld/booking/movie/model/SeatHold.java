package com.lld.booking.movie.model;

import java.time.Instant;
import java.util.List;

/**
 * Seats kept aside while the customer pays. ACTIVE → PAYING → CONFIRMED, or RELEASED / EXPIRED.
 * PAYING stops the expiry job from taking the seats away in the middle of a payment.
 */
public final class SeatHold {

    public enum Status {
        ACTIVE, PAYING, CONFIRMED, RELEASED, EXPIRED
    }

    private final String id;
    private final String userId;
    private final String showId;
    private final List<String> seatIds;
    private final long priceCents;
    private final Instant expiresAt;
    private Status status = Status.ACTIVE;

    public SeatHold(String id, String userId, String showId, List<String> seatIds, long priceCents, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.showId = showId;
        this.seatIds = List.copyOf(seatIds);
        this.priceCents = priceCents;
        this.expiresAt = expiresAt;
    }

    public String id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public String showId() {
        return showId;
    }

    public List<String> seatIds() {
        return seatIds;
    }

    public long priceCents() {
        return priceCents;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isLive() {
        return status == Status.ACTIVE || status == Status.PAYING;
    }

    @Override
    public String toString() {
        return id + " " + seatIds + " " + String.format("$%d.%02d", priceCents / 100, priceCents % 100)
                + " until " + expiresAt + " [" + status + "]";
    }
}
