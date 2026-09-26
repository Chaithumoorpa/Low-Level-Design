package com.lld.management.restaurant.model;

/**
 * A physical table. FREE -> OCCUPIED (party seated) -> CLEANING (bill paid) -> FREE (bussed).
 * Future bookings live in reservations, not in the table's status.
 */
public final class Table {

    public enum Status {
        FREE, OCCUPIED, CLEANING
    }

    private final String id;
    private final int seats;
    private Status status = Status.FREE;

    public Table(String id, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("A table needs seats");
        }
        this.id = id;
        this.seats = seats;
    }

    public String id() {
        return id;
    }

    public int seats() {
        return seats;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return id + "(" + seats + ")";
    }
}
