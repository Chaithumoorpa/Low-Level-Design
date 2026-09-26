package com.lld.management.restaurant.billing;

import com.lld.management.restaurant.model.Tab;

import java.util.Optional;

/** Sales tax on the running total (put it last so it also covers the service charge). */
public class TaxRule implements PricingRule {

    private final int basisPoints;

    public TaxRule(int basisPoints) {
        this.basisPoints = basisPoints;
    }

    @Override
    public Optional<BillLine> apply(Tab tab, long runningTotalCents) {
        long tax = PricingRule.percentOf(Math.max(0, runningTotalCents), basisPoints);
        return tax == 0 ? Optional.empty() : Optional.of(new BillLine("Tax " + basisPoints / 100.0 + "%", tax));
    }
}
