package com.lld.booking.ride.model;

/** Ride products with their tariff (cents): base + per km + per minute, never below the minimum. */
public enum VehicleType {
    //         base, per km, per min, minimum
    ECONOMY(250, 120, 25, 600),
    PREMIUM(500, 200, 40, 1200),
    XL(400, 180, 35, 1000);

    private final long base;
    private final long perKm;
    private final long perMinute;
    private final long minimum;

    VehicleType(long base, long perKm, long perMinute, long minimum) {
        this.base = base;
        this.perKm = perKm;
        this.perMinute = perMinute;
        this.minimum = minimum;
    }

    public long base() {
        return base;
    }

    public long perKm() {
        return perKm;
    }

    public long perMinute() {
        return perMinute;
    }

    public long minimum() {
        return minimum;
    }
}
