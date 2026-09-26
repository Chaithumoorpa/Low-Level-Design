package com.lld.states.vending.model;

import java.util.Map;

/** What falls into the tray: the product and the change coins. */
public record VendResult(String slotCode, Product product, Map<Integer, Integer> change) {

    public long changeAmount() {
        return Money.total(change);
    }

    @Override
    public String toString() {
        return product.name() + " from " + slotCode + ", change " + Money.format(changeAmount())
                + " [" + Money.describe(change) + "]";
    }
}
