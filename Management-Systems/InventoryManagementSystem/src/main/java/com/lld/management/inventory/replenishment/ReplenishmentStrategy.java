package com.lld.management.inventory.replenishment;

import com.lld.management.inventory.model.StockLevel;

/** How much to reorder once stock falls to the reorder point. */
public interface ReplenishmentStrategy {

    int quantityToOrder(StockLevel level);

    /** Always order the same batch size (e.g. a supplier's pallet). */
    static ReplenishmentStrategy fixed(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Order quantity must be positive");
        }
        return level -> quantity;
    }

    /** Order enough to bring available stock back up to a maximum shelf level. */
    static ReplenishmentStrategy topUpTo(int maxLevel) {
        return level -> Math.max(0, maxLevel - level.available());
    }
}
