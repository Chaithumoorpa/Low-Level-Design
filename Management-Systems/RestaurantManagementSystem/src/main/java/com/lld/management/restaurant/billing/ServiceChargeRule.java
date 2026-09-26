package com.lld.management.restaurant.billing;

import com.lld.management.restaurant.model.Tab;

import java.util.Optional;

/** Automatic gratuity for large parties, on the total after discounts. */
public class ServiceChargeRule implements PricingRule {

    private final int minPartySize;
    private final int basisPoints;

    public ServiceChargeRule(int minPartySize, int basisPoints) {
        this.minPartySize = minPartySize;
        this.basisPoints = basisPoints;
    }

    @Override
    public Optional<BillLine> apply(Tab tab, long runningTotalCents) {
        if (tab.partySize() < minPartySize || runningTotalCents <= 0) {
            return Optional.empty();
        }
        return Optional.of(new BillLine("Service " + basisPoints / 100.0 + "% (party of " + tab.partySize() + ")",
                PricingRule.percentOf(runningTotalCents, basisPoints)));
    }
}
