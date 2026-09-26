package com.lld.management.inventory.model;

/** Part of an order served from one warehouse: "take 3 of SKU-1 from WH-EAST". */
public record Allocation(String warehouseId, String sku, int quantity) {

    @Override
    public String toString() {
        return quantity + "x" + sku + "@" + warehouseId;
    }
}
