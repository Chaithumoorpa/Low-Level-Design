package com.lld.management.restaurant.billing;

import com.lld.management.restaurant.model.Tab;

import java.util.Optional;

/**
 * One step of the bill (Chain of Responsibility style: rules run in a configured order, each sees the
 * running total left by the ones before). Order matters: discounts, then service charge, then tax.
 */
public interface PricingRule {

    /** @return the line to add, or empty if the rule doesn't apply to this tab */
    Optional<BillLine> apply(Tab tab, long runningTotalCents);

    /** {@code amount * basisPoints / 10000}, rounded half up (100 bps = 1%). */
    static long percentOf(long amountCents, int basisPoints) {
        return Math.floorDiv(amountCents * basisPoints + 5_000, 10_000);
    }
}
