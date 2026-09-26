package com.lld.booking.ride.model;

/** A point on a city grid in km (a real system would use lat/long and road distances). */
public record Location(double x, double y) {

    public double distanceTo(Location o) {
        return Math.hypot(x - o.x, y - o.y);
    }

    @Override
    public String toString() {
        return String.format("(%.1f, %.1f)", x, y);
    }
}
