package com.lld.management.restaurant.billing;

import com.lld.management.restaurant.model.MenuItem;
import com.lld.management.restaurant.model.OrderItem;
import com.lld.management.restaurant.model.Tab;

import java.time.LocalTime;
import java.util.Optional;

/** Discount on one category (e.g. drinks) for items <em>ordered</em> inside a time window. */
public class HappyHourRule implements PricingRule {

    private final MenuItem.Category category;
    private final LocalTime from;
    private final LocalTime until;
    private final int basisPointsOff;

    public HappyHourRule(MenuItem.Category category, LocalTime from, LocalTime until, int basisPointsOff) {
        this.category = category;
        this.from = from;
        this.until = until;
        this.basisPointsOff = basisPointsOff;
    }

    @Override
    public Optional<BillLine> apply(Tab tab, long runningTotalCents) {
        long eligible = tab.items().stream()
                .filter(i -> i.status().billable() && i.item().category() == category)
                .filter(i -> {
                    LocalTime t = i.orderedAt().toLocalTime();
                    return !t.isBefore(from) && t.isBefore(until);
                })
                .mapToLong(OrderItem::lineTotalCents).sum();
        long discount = PricingRule.percentOf(eligible, basisPointsOff);
        return discount == 0 ? Optional.empty()
                : Optional.of(new BillLine("Happy hour " + basisPointsOff / 100 + "% off " + category.name().toLowerCase(), -discount));
    }
}
