package com.lld.booking.ride.pricing;

/**
 * Turns local demand and supply into a multiplier. When requests outnumber idle drivers nearby, the
 * price rises (in 0.1 steps) to bring more drivers in and spread demand; capped to stay fair.
 */
@FunctionalInterface
public interface SurgePolicy {

    /** @return multiplier in basis points (10,000 = no surge) */
    int surgeBps(int recentRequestsNearby, int idleDriversNearby);

    /** 1.0 until demand exceeds supply, then +0.5 per extra request per driver, rounded to 0.1, max 2.5. */
    static SurgePolicy standard() {
        return (demand, supply) -> {
            double ratio = (double) demand / Math.max(1, supply);
            if (ratio <= 1) {
                return 10_000;
            }
            long bps = Math.round((1 + 0.5 * (ratio - 1)) * 10) * 1_000;
            return (int) Math.min(25_000, bps);
        };
    }

    static SurgePolicy none() {
        return (demand, supply) -> 10_000;
    }
}
