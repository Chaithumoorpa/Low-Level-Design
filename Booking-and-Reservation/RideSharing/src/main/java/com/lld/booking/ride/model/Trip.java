package com.lld.booking.ride.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * MATCHING → DRIVER_ASSIGNED → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED
 * MATCHING → NO_DRIVERS            MATCHING / DRIVER_ASSIGNED / DRIVER_ARRIVED → CANCELLED
 * DRIVER_ASSIGNED → MATCHING (driver cancelled: find another)
 * </pre>
 */
public final class Trip {

    public enum Status {
        MATCHING, DRIVER_ASSIGNED, DRIVER_ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED, NO_DRIVERS;

        Set<Status> next() {
            return switch (this) {
                case MATCHING -> EnumSet.of(DRIVER_ASSIGNED, NO_DRIVERS, CANCELLED);
                case DRIVER_ASSIGNED -> EnumSet.of(DRIVER_ARRIVED, CANCELLED, MATCHING);
                case DRIVER_ARRIVED -> EnumSet.of(IN_PROGRESS, CANCELLED);
                case IN_PROGRESS -> EnumSet.of(COMPLETED);
                case COMPLETED, CANCELLED, NO_DRIVERS -> EnumSet.noneOf(Status.class);
            };
        }

        public boolean isFinal() {
            return next().isEmpty();
        }
    }

    private final String id;
    private final Rider rider;
    private final FareQuote quote;
    private final Instant requestedAt;
    private final Set<String> passedOn = new HashSet<>();           // drivers who declined / timed out / cancelled
    private final List<String> history = new ArrayList<>();
    private Status status = Status.MATCHING;
    private Driver driver;
    private Driver offeredTo;
    private Instant offerExpiresAt;
    private Instant assignedAt;
    private Instant startedAt;
    private long fareCents;
    private long cancellationFeeCents;
    private boolean riderRated;
    private boolean driverRated;

    public Trip(String id, Rider rider, FareQuote quote, Instant requestedAt) {
        this.id = id;
        this.rider = rider;
        this.quote = quote;
        this.requestedAt = requestedAt;
        history.add(requestedAt + " requested");
    }

    public String id() {
        return id;
    }

    public Rider rider() {
        return rider;
    }

    public FareQuote quote() {
        return quote;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Status status() {
        return status;
    }

    public Driver driver() {
        return driver;
    }

    public Driver offeredTo() {
        return offeredTo;
    }

    public Instant offerExpiresAt() {
        return offerExpiresAt;
    }

    public Instant assignedAt() {
        return assignedAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long fareCents() {
        return fareCents;
    }

    public long cancellationFeeCents() {
        return cancellationFeeCents;
    }

    public Set<String> passedOn() {
        return Set.copyOf(passedOn);
    }

    public List<String> history() {
        return List.copyOf(history);
    }

    public boolean riderRated() {
        return riderRated;
    }

    public boolean driverRated() {
        return driverRated;
    }

    // ---- service only

    public void move(Status to, Instant at, String note) {
        if (!status.next().contains(to)) {
            throw new RideException(id + " can't go from " + status + " to " + to);
        }
        status = to;
        if (to == Status.DRIVER_ASSIGNED) {
            assignedAt = at;
        }
        if (to == Status.IN_PROGRESS) {
            startedAt = at;
        }
        history.add(at + " " + to + (note.isEmpty() ? "" : " (" + note + ")"));
    }

    public void offer(Driver d, Instant expiresAt) {
        offeredTo = d;
        offerExpiresAt = expiresAt;
        if (d != null) {
            history.add("offered to " + d.name());
        }
    }

    public void passOn(String driverId) {
        passedOn.add(driverId);
    }

    public void setDriver(Driver d) {
        driver = d;
    }

    public void setFare(long cents) {
        fareCents = cents;
    }

    public void setCancellationFee(long cents) {
        cancellationFeeCents = cents;
    }

    public void markRiderRated() {
        riderRated = true;
    }

    public void markDriverRated() {
        driverRated = true;
    }

    @Override
    public String toString() {
        return id + " " + rider.name() + " " + quote.vehicle() + " [" + status
                + (driver == null ? "" : ", " + driver.name())
                + (fareCents > 0 ? String.format(", fare $%d.%02d", fareCents / 100, fareCents % 100) : "")
                + (cancellationFeeCents > 0 ? String.format(", fee $%d.%02d", cancellationFeeCents / 100, cancellationFeeCents % 100) : "")
                + "]";
    }
}
