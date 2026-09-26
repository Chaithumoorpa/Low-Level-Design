package com.lld.management.parkinglot.pricing;

import com.lld.management.parkinglot.model.VehicleType;

import java.time.Duration;

/** Strategy: what a stay costs, in cents. */
public interface PricingStrategy {

    long fee(VehicleType type, Duration stay);

    /** Charged when the ticket is lost (at least a full day in most garages). */
    long lostTicketFee(VehicleType type);
}
