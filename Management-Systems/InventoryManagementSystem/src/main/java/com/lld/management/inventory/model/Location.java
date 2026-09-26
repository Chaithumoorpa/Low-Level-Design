package com.lld.management.inventory.model;

/** A point on a simple map grid (km). Real systems would use lat/long and road distance. */
public record Location(double x, double y) {

    public double distanceTo(Location other) {
        return Math.hypot(x - other.x, y - other.y);
    }
}
