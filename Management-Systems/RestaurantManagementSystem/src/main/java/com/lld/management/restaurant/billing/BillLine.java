package com.lld.management.restaurant.billing;

/** One printed line of a bill; negative for discounts. */
public record BillLine(String label, long amountCents) {

    @Override
    public String toString() {
        return String.format("%-44s %9s", label, Bill.money(amountCents));
    }
}
