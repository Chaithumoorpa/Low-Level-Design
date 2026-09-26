package com.lld.management.parkinglot.model;

import java.util.List;

/**
 * Vehicle categories and the spots each may use, <b>in order of preference</b> (tightest fit first).
 * Keeping the rule here means adding a vehicle type is a one-line change.
 *
 * <p>Policy: EV spots are reserved for electric cars, so petrol cars never block a charger.
 */
public enum VehicleType {
    MOTORCYCLE(List.of(SpotSize.MOTORCYCLE, SpotSize.COMPACT, SpotSize.LARGE)),
    CAR(List.of(SpotSize.COMPACT, SpotSize.LARGE)),
    ELECTRIC_CAR(List.of(SpotSize.EV, SpotSize.COMPACT, SpotSize.LARGE)),
    VAN(List.of(SpotSize.LARGE)),
    TRUCK(List.of(SpotSize.LARGE));

    private final List<SpotSize> allowedSpots;

    VehicleType(List<SpotSize> allowedSpots) {
        this.allowedSpots = allowedSpots;
    }

    /** Spot sizes this vehicle fits in, best fit first. */
    public List<SpotSize> allowedSpots() {
        return allowedSpots;
    }

    public boolean fits(SpotSize size) {
        return allowedSpots.contains(size);
    }
}
