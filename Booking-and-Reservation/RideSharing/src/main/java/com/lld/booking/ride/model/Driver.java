package com.lld.booking.ride.model;

/**
 * OFFLINE → AVAILABLE → OFFERED (a trip is waiting for their answer) → ON_TRIP → AVAILABLE.
 * OFFERED is what stops two riders being offered the same driver at once.
 */
public final class Driver {

    public enum Status {
        OFFLINE, AVAILABLE, OFFERED, ON_TRIP
    }

    private final String id;
    private final String name;
    private final VehicleType vehicle;
    private final RatingTally rating = new RatingTally();
    private Location location;
    private Status status = Status.OFFLINE;

    public Driver(String id, String name, VehicleType vehicle, Location location) {
        this.id = id;
        this.name = name;
        this.vehicle = vehicle;
        this.location = location;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public VehicleType vehicle() {
        return vehicle;
    }

    public Location location() {
        return location;
    }

    public void moveTo(Location l) {
        location = l;
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
        return name + " (" + vehicle + ")";
    }
}
