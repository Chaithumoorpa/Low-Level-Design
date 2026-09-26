package com.lld.booking.ride.pricing;

import com.lld.booking.ride.model.VehicleType;

/** Tariff × surge, never below the product's minimum fare. Surge is in basis points (15,000 = ×1.5). */
public final class FareCalculator {

    public long fare(VehicleType type, double distanceKm, long minutes, int surgeBps) {
        double raw = type.base() + type.perKm() * distanceKm + type.perMinute() * minutes;
        long surged = Math.round(raw * surgeBps / 10_000.0);
        return Math.max(type.minimum(), surged);
    }
}
