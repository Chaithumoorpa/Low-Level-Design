package com.lld.management.inventory;

import com.lld.management.inventory.core.InventoryListener;
import com.lld.management.inventory.core.InventoryService;
import com.lld.management.inventory.core.ManualClock;
import com.lld.management.inventory.fulfillment.SplitShipmentStrategy;
import com.lld.management.inventory.model.InventoryException;
import com.lld.management.inventory.model.Location;
import com.lld.management.inventory.model.OrderLine;
import com.lld.management.inventory.model.Product;
import com.lld.management.inventory.model.PurchaseOrder;
import com.lld.management.inventory.model.Reservation;
import com.lld.management.inventory.model.StockLevel;
import com.lld.management.inventory.model.Warehouse;
import com.lld.management.inventory.replenishment.ReorderPolicy;
import com.lld.management.inventory.replenishment.ReplenishmentStrategy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** A small online shop with two warehouses, on a simulated clock. */
public class InventoryApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2026-07-01T10:00:00Z"));
        InventoryService inventory = new InventoryService(new SplitShipmentStrategy(), Duration.ofMinutes(15), clock);
        inventory.addListener(new InventoryListener() {
            @Override
            public void onLowStock(StockLevel level, PurchaseOrder raised) {
                System.out.println("   [low stock] " + level.sku() + "@" + level.warehouseId() + " available "
                        + level.available() + " -> raised " + raised);
            }

            @Override
            public void onOutOfStock(String warehouseId, String sku) {
                System.out.println("   [out of stock] " + sku + "@" + warehouseId);
            }
        });

        inventory.addProduct(new Product("MUG", "Coffee mug", "kitchen", 350));
        inventory.addProduct(new Product("LAMP", "Desk lamp", "home", 2200));
        inventory.addProduct(new Product("PEN", "Gel pen", "office", 60));
        Warehouse west = new Warehouse("WEST", "West DC", new Location(0, 0));
        Warehouse east = new Warehouse("EAST", "East DC", new Location(100, 0));
        inventory.addWarehouse(west);
        inventory.addWarehouse(east);
        Location nearWest = new Location(10, 0);

        step("Stock arrives");
        inventory.receive("WEST", "MUG", 10, "initial load");
        inventory.receive("EAST", "MUG", 8, "initial load");
        inventory.receive("WEST", "LAMP", 3, "initial load");
        inventory.receive("EAST", "LAMP", 5, "initial load");
        inventory.receive("WEST", "PEN", 200, "initial load");
        inventory.setReorderPolicy("WEST", "MUG", new ReorderPolicy(4, ReplenishmentStrategy.topUpTo(20)));
        inventory.setReorderPolicy("WEST", "LAMP", new ReorderPolicy(1, ReplenishmentStrategy.fixed(10)));
        printStock(inventory);

        step("Order A: 2 mugs + 1 lamp, customer near WEST");
        Reservation a = inventory.reserve("A", List.of(new OrderLine("MUG", 2), new OrderLine("LAMP", 1)), nearWest);
        System.out.println("   " + a + " -> " + a.shipments() + " parcel(s)");
        System.out.println("   retry of order A returns " + inventory.reserve("A", List.of(new OrderLine("MUG", 2)), nearWest).id());

        step("Order B: 6 lamps near WEST (WEST has 2 free, EAST 5)");
        Reservation b = inventory.reserve("B", List.of(new OrderLine("LAMP", 6)), nearWest);
        System.out.println("   " + b + " -> " + b.shipments() + " parcel(s): no single warehouse had 6 lamps free");

        step("Order C: 20 lamps");
        attempt(() -> inventory.reserve("C", List.of(new OrderLine("LAMP", 20)), nearWest));

        step("Orders A and B are paid and ship");
        inventory.commit(a.id());
        inventory.commit(b.id());
        System.out.println("   " + a);
        System.out.println("   " + b);

        step("Order D: 6 mugs, customer abandons checkout");
        Reservation d = inventory.reserve("D", List.of(new OrderLine("MUG", 6)), nearWest);
        System.out.println("   " + d);
        System.out.println("   storefront shows MUG available: " + inventory.available("MUG"));
        clock.advance(Duration.ofMinutes(20));
        System.out.println("   20 min later: expired " + inventory.expireReservations() + " reservation(s); MUG available: "
                + inventory.available("MUG"));
        attempt(() -> {
            inventory.commit(d.id());
            return "shipped";
        });

        step("Cycle count finds 3 broken mugs at WEST; then a transfer EAST -> WEST");
        inventory.adjust("WEST", "MUG", -3, "count sheet 7/1: 3 broken");
        inventory.transfer("MUG", "EAST", "WEST", 5, "TR-1");
        attempt(() -> {
            inventory.adjust("EAST", "LAMP", -10, "bad count");
            return "adjusted";
        });

        step("Supplier delivers the open purchase orders");
        for (PurchaseOrder po : inventory.openPurchaseOrders()) {
            inventory.receivePurchaseOrder(po.id());
            System.out.println("   received " + po);
        }
        printStock(inventory);

        step("Ledger for MUG@WEST (the stock level is the sum of these lines)");
        inventory.ledger().stream()
                .filter(m -> m.sku().equals("MUG") && m.warehouseId().equals("WEST"))
                .forEach(m -> System.out.println("   " + m));
        System.out.printf("%nStock value: $%.2f%n", inventory.stockValueCents() / 100.0);
    }

    private static void printStock(InventoryService inventory) {
        System.out.println("   sku    wh     onHand reserved available");
        for (String sku : List.of("MUG", "LAMP", "PEN")) {
            for (Warehouse w : inventory.warehouses()) {
                StockLevel s = inventory.stockLevel(w.id(), sku);
                if (s.onHand() > 0 || s.reserved() > 0) {
                    System.out.printf("   %-6s %-6s %6d %8d %9d%n", sku, w.id(), s.onHand(), s.reserved(), s.available());
                }
            }
        }
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (InventoryException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
