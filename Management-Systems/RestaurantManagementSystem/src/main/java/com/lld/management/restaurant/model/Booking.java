package com.lld.management.restaurant.model;

import java.time.LocalDateTime;

/** A table reservation for a time slot [start, end). */
public final class Booking {

    public enum Status {
        BOOKED, SEATED, COMPLETED, CANCELLED, NO_SHOW
    }

    private final String id;
    private final String guestName;
    private final int partySize;
    private final Table table;
    private final LocalDateTime start;
    private final LocalDateTime end;
    private Status status = Status.BOOKED;

    public Booking(String id, String guestName, int partySize, Table table, LocalDateTime start, LocalDateTime end) {
        this.id = id;
        this.guestName = guestName;
        this.partySize = partySize;
        this.table = table;
        this.start = start;
        this.end = end;
    }

    public String id() {
        return id;
    }

    public String guestName() {
        return guestName;
    }

    public int partySize() {
        return partySize;
    }

    public Table table() {
        return table;
    }

    public LocalDateTime start() {
        return start;
    }

    public LocalDateTime end() {
        return end;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    /** Still holds its table (booked or currently seated). */
    public boolean isActive() {
        return status == Status.BOOKED || status == Status.SEATED;
    }

    public boolean overlaps(LocalDateTime from, LocalDateTime to) {
        return start.isBefore(to) && from.isBefore(end);
    }

    @Override
    public String toString() {
        return id + " " + guestName + " x" + partySize + " at " + table.id() + " " + start.toLocalTime()
                + "-" + end.toLocalTime() + " [" + status + "]";
    }
}
