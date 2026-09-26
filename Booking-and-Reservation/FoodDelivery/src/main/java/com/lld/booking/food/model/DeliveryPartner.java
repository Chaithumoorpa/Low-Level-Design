package com.lld.booking.food.model;

/** A courier. OFFLINE → AVAILABLE (online, idle) → BUSY (carrying one order) → AVAILABLE. */
public final class DeliveryPartner {

    public enum Status {
        OFFLINE, AVAILABLE, BUSY
    }

    private final String id;
    private final String name;
    private final RatingTally rating = new RatingTally();
    private Location location;
    private Status status = Status.OFFLINE;

    public DeliveryPartner(String id, String name, Location location) {
        this.id = id;
        this.name = name;
        this.location = location;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Location location() {
        return location;
    }

    public void moveTo(Location l) {
        this.location = l;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public RatingTally rating() {
        return rating;
    }

    @Override
    public String toString() {
        return name;
    }
}
