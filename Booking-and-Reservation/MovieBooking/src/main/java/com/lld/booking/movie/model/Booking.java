package com.lld.booking.movie.model;

import java.time.Instant;
import java.util.List;

/** Paid tickets. CONFIRMED → CANCELLED (with a refund decided by the refund policy). */
public final class Booking {

    public enum Status {
        CONFIRMED, CANCELLED
    }

    private final String id;
    private final String userId;
    private final String showId;
    private final List<String> seatIds;
    private final long amountCents;
    private final String paymentReference;
    private final Instant bookedAt;
    private Status status = Status.CONFIRMED;
    private long refundedCents;

    public Booking(String id, String userId, String showId, List<String> seatIds, long amountCents,
                   String paymentReference, Instant bookedAt) {
        this.id = id;
        this.userId = userId;
        this.showId = showId;
        this.seatIds = List.copyOf(seatIds);
        this.amountCents = amountCents;
        this.paymentReference = paymentReference;
        this.bookedAt = bookedAt;
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

    public long amountCents() {
        return amountCents;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public Instant bookedAt() {
        return bookedAt;
    }

    public Status status() {
        return status;
    }

    public long refundedCents() {
        return refundedCents;
    }

    public void cancel(long refund) {
        status = Status.CANCELLED;
        refundedCents = refund;
    }

    @Override
    public String toString() {
        return id + " " + userId + " " + seatIds + " " + String.format("$%d.%02d", amountCents / 100, amountCents % 100)
                + " [" + status + (refundedCents > 0 ? String.format(", refunded $%d.%02d", refundedCents / 100, refundedCents % 100) : "") + "]";
    }
}
