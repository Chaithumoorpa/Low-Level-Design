package com.lld.booking.ride.model;

import java.time.Instant;

/**
 * An upfront price shown before booking. The surge multiplier is <b>locked</b> here: if the rider books
 * within the validity window, they pay at this multiplier even if surge rises meanwhile.
 */
public record FareQuote(String id, String riderId, Location pickup, Location dropoff, VehicleType vehicle,
                        double distanceKm, long minutes, int surgeBps, long estimateCents, Instant expiresAt) {

    @Override
    public String toString() {
        return String.format("%s %s %.1f km ~%d min, surge x%.2f, estimate $%d.%02d (valid until %s)", id, vehicle,
                distanceKm, minutes, surgeBps / 10_000.0, estimateCents / 100, estimateCents % 100, expiresAt);
    }
}
