package com.lld.management.inventory.fulfillment;

import com.lld.management.inventory.model.Allocation;
import com.lld.management.inventory.model.Location;
import com.lld.management.inventory.model.OrderLine;
import com.lld.management.inventory.model.Warehouse;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which warehouse(s) serve an order. Pure function over a stock snapshot: it doesn't change
 * anything, so it is easy to test and swap. The inventory service calls it while holding the locks
 * of every SKU in the order, then applies the plan.
 */
public interface FulfillmentStrategy {

    /**
     * @param lines     one line per SKU (already merged)
     * @param available sku -> warehouseId -> available quantity
     * @return the allocations covering every line in full, or empty if the order can't be met
     */
    Optional<List<Allocation>> plan(List<OrderLine> lines, Map<String, Map<String, Integer>> available,
                                    List<Warehouse> warehouses, Location shipTo);

    /** Nearest first; ties broken by id so plans are deterministic. */
    static List<Warehouse> byDistance(List<Warehouse> warehouses, Location shipTo) {
        return warehouses.stream()
                .sorted(Comparator.comparingDouble((Warehouse w) -> w.location().distanceTo(shipTo))
                        .thenComparing(Warehouse::id))
                .toList();
    }

    static int availableAt(Map<String, Map<String, Integer>> available, String sku, String warehouseId) {
        return available.getOrDefault(sku, Map.of()).getOrDefault(warehouseId, 0);
    }
}
