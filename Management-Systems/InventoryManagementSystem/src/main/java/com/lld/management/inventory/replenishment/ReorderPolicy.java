package com.lld.management.inventory.replenishment;

/**
 * "When available stock drops to {@code reorderPoint} or below, raise a purchase order sized by
 * {@code strategy}." Set per warehouse and SKU. The reorder point usually covers the demand expected
 * during the supplier's lead time plus some safety stock.
 */
public record ReorderPolicy(int reorderPoint, ReplenishmentStrategy strategy) {

    public ReorderPolicy {
        if (reorderPoint < 0) {
            throw new IllegalArgumentException("Negative reorder point");
        }
    }
}
