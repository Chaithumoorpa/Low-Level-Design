package com.lld.management.inventory.model;

/** A catalogue item. The SKU (stock keeping unit) is the key everything else refers to. */
public record Product(String sku, String name, String category, long unitCostCents) {

    public Product {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU is required");
        }
        if (unitCostCents < 0) {
            throw new IllegalArgumentException("Negative cost");
        }
    }
}
