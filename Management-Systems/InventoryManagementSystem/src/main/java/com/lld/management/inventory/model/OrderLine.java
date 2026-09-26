package com.lld.management.inventory.model;

/** "I want this many of this SKU." */
public record OrderLine(String sku, int quantity) {

    public OrderLine {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
    }
}
