package com.lld.management.parkinglot.model;

/**
 * One marked bay, e.g. "F1-C07" (floor 1, compact, number 7). Lower numbers are closer to the
 * floor's entrance and lifts, which is what "nearest" means for allocation.
 *
 * <p>Mutable status, changed only by its floor while holding the lot's allocation lock.
 */
public final class ParkingSpot implements Comparable<ParkingSpot> {

    private final String id;
    private final int floor;
    private final int number;
    private final SpotSize size;
    private SpotStatus status = SpotStatus.FREE;
    private Vehicle vehicle;

    public ParkingSpot(int floor, int number, SpotSize size) {
        this.floor = floor;
        this.number = number;
        this.size = size;
        this.id = "F" + floor + "-" + size.name().charAt(0) + String.format("%02d", number);
    }

    public String id() {
        return id;
    }

    public int floor() {
        return floor;
    }

    public int number() {
        return number;
    }

    public SpotSize size() {
        return size;
    }

    public SpotStatus status() {
        return status;
    }

    public Vehicle vehicle() {
        return vehicle;
    }

    public boolean isFree() {
        return status == SpotStatus.FREE;
    }

    public void occupy(Vehicle v) {
        if (status != SpotStatus.FREE) {
            throw new IllegalStateException(id + " is " + status);
        }
        if (!v.type().fits(size)) {
            throw new IllegalArgumentException(v.type() + " does not fit a " + size + " spot");
        }
        vehicle = v;
        status = SpotStatus.OCCUPIED;
    }

    public void release() {
        if (status != SpotStatus.OCCUPIED) {
            throw new IllegalStateException(id + " is not occupied");
        }
        vehicle = null;
        status = SpotStatus.FREE;
    }

    public void setOutOfService(boolean outOfService) {
        if (outOfService) {
            if (status == SpotStatus.OCCUPIED) {
                throw new IllegalStateException(id + " is occupied; wait until it is free");
            }
            status = SpotStatus.OUT_OF_SERVICE;
        } else if (status == SpotStatus.OUT_OF_SERVICE) {
            status = SpotStatus.FREE;
        }
    }

    /** Nearest first: by floor, then by number. */
    @Override
    public int compareTo(ParkingSpot o) {
        int byFloor = Integer.compare(floor, o.floor);
        return byFloor != 0 ? byFloor : Integer.compare(number, o.number);
    }

    @Override
    public String toString() {
        return id;
    }
}
