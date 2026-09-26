package com.lld.management.inventory.fulfillment;

import com.lld.management.inventory.model.Allocation;
import com.lld.management.inventory.model.Location;
import com.lld.management.inventory.model.OrderLine;
import com.lld.management.inventory.model.Warehouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prefer one parcel (like {@link SingleWarehouseStrategy}); if no single warehouse can do it, fill each
 * line greedily from the nearest warehouses outward. Serves more orders at the cost of extra parcels.
 */
public class SplitShipmentStrategy implements FulfillmentStrategy {

    private final SingleWarehouseStrategy single = new SingleWarehouseStrategy();

    @Override
    public Optional<List<Allocation>> plan(List<OrderLine> lines, Map<String, Map<String, Integer>> available,
                                           List<Warehouse> warehouses, Location shipTo) {
        Optional<List<Allocation>> onePackage = single.plan(lines, available, warehouses, shipTo);
        if (onePackage.isPresent()) {
            return onePackage;
        }
        List<Warehouse> ordered = FulfillmentStrategy.byDistance(warehouses, shipTo);
        List<Allocation> plan = new ArrayList<>();
        for (OrderLine line : lines) {
            int remaining = line.quantity();
            for (Warehouse w : ordered) {
                int take = Math.min(remaining, FulfillmentStrategy.availableAt(available, line.sku(), w.id()));
                if (take > 0) {
                    plan.add(new Allocation(w.id(), line.sku(), take));
                    remaining -= take;
                }
                if (remaining == 0) {
                    break;
                }
            }
            if (remaining > 0) {
                return Optional.empty();
            }
        }
        return Optional.of(plan);
    }
}
