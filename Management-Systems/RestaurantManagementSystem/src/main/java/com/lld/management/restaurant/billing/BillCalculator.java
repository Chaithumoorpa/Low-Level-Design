package com.lld.management.restaurant.billing;

import com.lld.management.restaurant.model.OrderItem;
import com.lld.management.restaurant.model.Tab;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a bill from a tab: billable items, then each {@link PricingRule} in order. Cancelled items
 * are left out; voided (comped) items are printed at $0.00 so the guest sees them.
 */
public final class BillCalculator {

    private final List<PricingRule> rules;

    public BillCalculator(List<PricingRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public Bill bill(Tab tab) {
        List<BillLine> items = new ArrayList<>();
        long subtotal = 0;
        for (OrderItem i : tab.items()) {
            switch (i.status()) {
                case CANCELLED -> { }
                case VOIDED -> items.add(new BillLine(i.quantity() + "x " + i.item().name() + " (comp: " + i.voidReason() + ")", 0));
                default -> {
                    items.add(new BillLine(i.quantity() + "x " + i.item().name(), i.lineTotalCents()));
                    subtotal += i.lineTotalCents();
                }
            }
        }
        long running = subtotal;
        List<BillLine> adjustments = new ArrayList<>();
        for (PricingRule rule : rules) {
            var line = rule.apply(tab, running);
            if (line.isPresent()) {
                adjustments.add(line.get());
                running += line.get().amountCents();
            }
        }
        return new Bill(items, subtotal, adjustments, running);
    }

    /**
     * Splits {@code totalCents} into {@code ways} shares that differ by at most one cent and add up
     * exactly (the first shares take the leftover cents).
     */
    public static List<Long> splitEvenly(long totalCents, int ways) {
        if (ways <= 0) {
            throw new IllegalArgumentException("Split needs at least one person");
        }
        long base = totalCents / ways;
        long remainder = totalCents % ways;
        List<Long> shares = new ArrayList<>();
        for (int i = 0; i < ways; i++) {
            shares.add(base + (i < remainder ? 1 : 0));
        }
        return shares;
    }
}
