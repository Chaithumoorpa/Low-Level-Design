package com.lld.management.inventory.fulfillment;

import com.lld.management.inventory.model.Allocation;
import com.lld.management.inventory.model.Location;
import com.lld.management.inventory.model.OrderLine;
import com.lld.management.inventory.model.Warehouse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One parcel or nothing: the nearest warehouse that can supply <em>every</em> line in full.
 * Cheapest shipping, but may refuse orders the network as a whole could have served.
 */
public class SingleWarehouseStrategy implements FulfillmentStrategy {

    @Override
    public Optional<List<Allocation>> plan(List<OrderLine> lines, Map<String, Map<String, Integer>> available,
                                           List<Warehouse> warehouses, Location shipTo) {
        for (Warehouse w : FulfillmentStrategy.byDistance(warehouses, shipTo)) {
            boolean hasAll = lines.stream()
                    .allMatch(l -> FulfillmentStrategy.availableAt(available, l.sku(), w.id()) >= l.quantity());
            if (hasAll) {
                return Optional.of(lines.stream().map(l -> new Allocation(w.id(), l.sku(), l.quantity())).toList());
            }
        }
        return Optional.empty();
    }
}
