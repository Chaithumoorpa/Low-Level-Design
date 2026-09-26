package com.lld.management.inventory;

import com.lld.management.inventory.core.InventoryListener;
import com.lld.management.inventory.core.InventoryService;
import com.lld.management.inventory.core.ManualClock;
import com.lld.management.inventory.fulfillment.FulfillmentStrategy;
import com.lld.management.inventory.fulfillment.SingleWarehouseStrategy;
import com.lld.management.inventory.fulfillment.SplitShipmentStrategy;
import com.lld.management.inventory.model.Allocation;
import com.lld.management.inventory.model.InventoryException;
import com.lld.management.inventory.model.Location;
import com.lld.management.inventory.model.OrderLine;
import com.lld.management.inventory.model.Product;
import com.lld.management.inventory.model.PurchaseOrder;
import com.lld.management.inventory.model.Reservation;
import com.lld.management.inventory.model.StockLevel;
import com.lld.management.inventory.model.StockMovement;
import com.lld.management.inventory.model.Warehouse;
import com.lld.management.inventory.replenishment.ReorderPolicy;
import com.lld.management.inventory.replenishment.ReplenishmentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryServiceTest {

    private static final Location NEAR_WEST = new Location(10, 0);
    private static final Location NEAR_EAST = new Location(90, 0);

    private ManualClock clock;
    private InventoryService inv;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-07-01T10:00:00Z"));
        inv = service(new SplitShipmentStrategy());
    }

    /** Whatever a test did, the ledger must explain every stock level. */
    @AfterEach
    void ledgerExplainsEveryStockLevel() {
        assertLedgerConsistent(inv);
    }

    private InventoryService service(FulfillmentStrategy strategy) {
        InventoryService s = new InventoryService(strategy, Duration.ofMinutes(15), clock);
        s.addProduct(new Product("MUG", "Mug", "kitchen", 350));
        s.addProduct(new Product("LAMP", "Lamp", "home", 2200));
        s.addWarehouse(new Warehouse("WEST", "West", new Location(0, 0)));
        s.addWarehouse(new Warehouse("EAST", "East", new Location(100, 0)));
        return s;
    }

    private static List<OrderLine> lines(Object... skuQty) {
        List<OrderLine> out = new ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) {
            out.add(new OrderLine((String) skuQty[i], (Integer) skuQty[i + 1]));
        }
        return out;
    }

    private static void assertLedgerConsistent(InventoryService s) {
        Map<String, int[]> sums = new HashMap<>();
        for (StockMovement m : s.ledger()) {
            int[] t = sums.computeIfAbsent(m.warehouseId() + "|" + m.sku(), k -> new int[2]);
            t[0] += m.onHandDelta();
            t[1] += m.reservedDelta();
        }
        for (Warehouse w : s.warehouses()) {
            for (String sku : List.of("MUG", "LAMP")) {
                StockLevel level = s.stockLevel(w.id(), sku);
                int[] t = sums.getOrDefault(w.id() + "|" + sku, new int[2]);
                assertEquals(t[0], level.onHand(), "onHand of " + sku + "@" + w);
                assertEquals(t[1], level.reserved(), "reserved of " + sku + "@" + w);
                assertTrue(level.reserved() >= 0 && level.available() >= 0, level.toString());
            }
        }
    }

    // ------------------------------------------------------------------ basics

    @Nested
    class Basics {

        @Test
        void receiveAddsStockAndUnknownsAreRejected() {
            inv.receive("WEST", "MUG", 10, "load");
            inv.receive("EAST", "MUG", 5, "load");
            assertEquals(new StockLevel("WEST", "MUG", 10, 0), inv.stockLevel("WEST", "MUG"));
            assertEquals(15, inv.available("MUG"));
            assertEquals(0, inv.available("LAMP"));
            assertThrows(InventoryException.class, () -> inv.receive("NORTH", "MUG", 1, "x"));
            assertThrows(InventoryException.class, () -> inv.receive("WEST", "SOFA", 1, "x"));
            assertThrows(InventoryException.class, () -> inv.receive("WEST", "MUG", 0, "x"));
            assertThrows(InventoryException.class, () -> inv.addProduct(new Product("MUG", "again", "k", 1)));
            assertThrows(IllegalArgumentException.class, () -> new OrderLine("MUG", 0));
        }

        @Test
        void stockValueUsesUnitCost() {
            inv.receive("WEST", "MUG", 10, "load");
            inv.receive("EAST", "LAMP", 2, "load");
            assertEquals(10 * 350 + 2 * 2200, inv.stockValueCents());
        }
    }

    // ------------------------------------------------------------------ reservations

    @Nested
    class Reservations {

        @BeforeEach
        void stock() {
            inv.receive("WEST", "MUG", 10, "load");
            inv.receive("EAST", "MUG", 10, "load");
            inv.receive("WEST", "LAMP", 2, "load");
            inv.receive("EAST", "LAMP", 5, "load");
        }

        @Test
        void nearestWarehouseThatHasEverythingWins() {
            Reservation r = inv.reserve("o1", lines("MUG", 2, "LAMP", 1), NEAR_WEST);
            assertEquals(List.of(new Allocation("WEST", "MUG", 2), new Allocation("WEST", "LAMP", 1)), r.allocations());
            Reservation e = inv.reserve("o2", lines("MUG", 2), NEAR_EAST);
            assertEquals(List.of(new Allocation("EAST", "MUG", 2)), e.allocations());
        }

        @Test
        void farWarehouseBeatsSplittingWhenItHasEverything() {
            Reservation r = inv.reserve("o1", lines("LAMP", 4), NEAR_WEST);
            assertEquals(List.of(new Allocation("EAST", "LAMP", 4)), r.allocations());
            assertEquals(1, r.shipments());
        }

        @Test
        void splitsNearestFirstWhenNoWarehouseHasEnough() {
            Reservation r = inv.reserve("o1", lines("LAMP", 6), NEAR_WEST);
            assertEquals(List.of(new Allocation("WEST", "LAMP", 2), new Allocation("EAST", "LAMP", 4)), r.allocations());
            assertEquals(2, r.shipments());
            assertEquals(1, inv.available("LAMP"));
        }

        @Test
        void singleWarehouseStrategyRefusesSplits() {
            InventoryService single = service(new SingleWarehouseStrategy());
            single.receive("WEST", "LAMP", 2, "load");
            single.receive("EAST", "LAMP", 5, "load");
            InventoryException e = assertThrows(InventoryException.class,
                    () -> single.reserve("o1", lines("LAMP", 6), NEAR_WEST));
            assertTrue(e.getMessage().contains("No single warehouse"), e.getMessage());
            assertEquals(7, single.available("LAMP"));
            assertLedgerConsistent(single);
        }

        @Test
        void allOrNothingWhenOneLineIsShort() {
            int ledgerBefore = inv.ledger().size();
            InventoryException e = assertThrows(InventoryException.class,
                    () -> inv.reserve("o1", lines("MUG", 3, "LAMP", 8), NEAR_WEST));
            assertEquals("Not enough stock for order o1: LAMP needs 8, 7 available", e.getMessage());
            assertEquals(20, inv.available("MUG"), "the mug line was not reserved either");
            assertEquals(ledgerBefore, inv.ledger().size());
        }

        @Test
        void duplicateLinesAreMerged() {
            Reservation r = inv.reserve("o1", lines("MUG", 6, "MUG", 6), NEAR_WEST);
            assertEquals(List.of(new Allocation("WEST", "MUG", 10), new Allocation("EAST", "MUG", 2)), r.allocations(),
                    "6 + 6 = 12 mugs; no warehouse has 12, so split nearest first");
        }

        @Test
        void retryWithSameOrderIdDoesNotReserveTwice() {
            Reservation first = inv.reserve("o1", lines("MUG", 4), NEAR_WEST);
            assertSame(first, inv.reserve("o1", lines("MUG", 4), NEAR_WEST));
            assertEquals(16, inv.available("MUG"));
            inv.release(first.id());
            Reservation again = inv.reserve("o1", lines("MUG", 4), NEAR_WEST);
            assertTrue(again != first && again.status() == Reservation.Status.ACTIVE);
        }

        @Test
        void commitShipsAndCanOnlyHappenOnce() {
            Reservation r = inv.reserve("o1", lines("MUG", 4), NEAR_WEST);
            assertEquals(new StockLevel("WEST", "MUG", 10, 4), inv.stockLevel("WEST", "MUG"));
            inv.commit(r.id());
            assertEquals(new StockLevel("WEST", "MUG", 6, 0), inv.stockLevel("WEST", "MUG"));
            assertEquals(Reservation.Status.COMMITTED, r.status());
            assertThrows(InventoryException.class, () -> inv.commit(r.id()));
            assertThrows(InventoryException.class, () -> inv.release(r.id()), "already shipped");
        }

        @Test
        void releaseReturnsStockAndIsIdempotent() {
            Reservation r = inv.reserve("o1", lines("MUG", 4, "LAMP", 1), NEAR_WEST);
            inv.release(r.id());
            inv.release(r.id());
            assertEquals(20, inv.available("MUG"));
            assertEquals(7, inv.available("LAMP"));
            assertThrows(InventoryException.class, () -> inv.commit(r.id()));
        }

        @Test
        void expiredReservationsFreeStockAndCantBeCommitted() {
            Reservation old = inv.reserve("o1", lines("MUG", 4), NEAR_WEST);
            clock.advance(Duration.ofMinutes(10));
            Reservation fresh = inv.reserve("o2", lines("MUG", 3), NEAR_WEST);
            clock.advance(Duration.ofMinutes(5));                               // old is exactly 15 min
            assertEquals(1, inv.expireReservations());
            assertEquals(Reservation.Status.EXPIRED, old.status());
            assertEquals(Reservation.Status.ACTIVE, fresh.status());
            assertEquals(17, inv.available("MUG"));
            assertEquals(0, inv.expireReservations());

            clock.advance(Duration.ofMinutes(20));
            InventoryException e = assertThrows(InventoryException.class, () -> inv.commit(fresh.id()));
            assertTrue(e.getMessage().contains("expired"));
            assertEquals(Reservation.Status.EXPIRED, fresh.status());
            assertEquals(20, inv.available("MUG"));
        }
    }

    // ------------------------------------------------------------------ corrections & transfers

    @Test
    void adjustCantDropBelowReservedStock() {
        inv.receive("WEST", "MUG", 10, "load");
        inv.reserve("o1", lines("MUG", 7), NEAR_WEST);
        inv.adjust("WEST", "MUG", -3, "broken");
        assertThrows(InventoryException.class, () -> inv.adjust("WEST", "MUG", -1, "more broken"));
        inv.adjust("WEST", "MUG", 2, "found behind shelf");
        assertEquals(new StockLevel("WEST", "MUG", 9, 7), inv.stockLevel("WEST", "MUG"));
    }

    @Test
    void transferMovesOnlyAvailableStock() {
        inv.receive("EAST", "MUG", 10, "load");
        inv.reserve("o1", lines("MUG", 6), NEAR_EAST);
        assertThrows(InventoryException.class, () -> inv.transfer("MUG", "EAST", "WEST", 5, "T1"));
        assertThrows(InventoryException.class, () -> inv.transfer("MUG", "EAST", "EAST", 1, "T1"));
        inv.transfer("MUG", "EAST", "WEST", 4, "T1");
        assertEquals(new StockLevel("EAST", "MUG", 6, 6), inv.stockLevel("EAST", "MUG"));
        assertEquals(new StockLevel("WEST", "MUG", 4, 0), inv.stockLevel("WEST", "MUG"));
    }

    // ------------------------------------------------------------------ replenishment

    @Nested
    class Replenishment {

        private final List<String> events = new ArrayList<>();

        @BeforeEach
        void listen() {
            inv.addListener(new InventoryListener() {
                @Override
                public void onLowStock(StockLevel level, PurchaseOrder raised) {
                    events.add("low " + level.sku() + "@" + level.warehouseId() + " " + raised.quantity());
                }

                @Override
                public void onOutOfStock(String warehouseId, String sku) {
                    events.add("out " + sku + "@" + warehouseId);
                }
            });
        }

        @Test
        void oneOpenPurchaseOrderPerBinUntilReceived() {
            inv.receive("WEST", "MUG", 10, "load");
            inv.setReorderPolicy("WEST", "MUG", new ReorderPolicy(5, ReplenishmentStrategy.topUpTo(20)));
            assertEquals(List.of(), inv.openPurchaseOrders());

            inv.reserve("o1", lines("MUG", 5), NEAR_WEST);                      // available 5 -> reorder
            inv.reserve("o2", lines("MUG", 3), NEAR_WEST);                      // still low, no duplicate PO
            assertEquals(1, inv.openPurchaseOrders().size());
            PurchaseOrder po = inv.openPurchaseOrders().get(0);
            assertEquals(15, po.quantity(), "top up 5 -> 20");
            assertEquals(List.of("low MUG@WEST 15"), events);

            inv.receivePurchaseOrder(po.id());
            assertEquals(new StockLevel("WEST", "MUG", 25, 8), inv.stockLevel("WEST", "MUG"));
            assertEquals(List.of(), inv.openPurchaseOrders());
            assertThrows(InventoryException.class, () -> inv.receivePurchaseOrder(po.id()));
        }

        @Test
        void stillLowAfterDeliveryRaisesTheNextOrder() {
            inv.receive("WEST", "LAMP", 3, "load");
            inv.setReorderPolicy("WEST", "LAMP", new ReorderPolicy(10, ReplenishmentStrategy.fixed(4)));
            PurchaseOrder first = inv.openPurchaseOrders().get(0);               // raised at once: 3 <= 10
            inv.receivePurchaseOrder(first.id());                              // 7 available, still <= 10
            PurchaseOrder second = inv.openPurchaseOrders().get(0);
            assertEquals(List.of("PO-1", "PO-2"), List.of(first.id(), second.id()));
            assertEquals(4, second.quantity());
        }

        @Test
        void outOfStockEventWhenAvailableHitsZero() {
            inv.receive("EAST", "LAMP", 2, "load");
            inv.reserve("o1", lines("LAMP", 2), NEAR_EAST);
            assertEquals(List.of("out LAMP@EAST"), events);
        }
    }

    // ------------------------------------------------------------------ strategies directly

    @Test
    void distanceTiesAreBrokenByWarehouseId() {
        List<Warehouse> ws = List.of(new Warehouse("B", "b", new Location(5, 0)), new Warehouse("A", "a", new Location(-5, 0)));
        assertEquals(List.of("A", "B"), FulfillmentStrategy.byDistance(ws, new Location(0, 0)).stream().map(Warehouse::id).toList());
    }

    // ------------------------------------------------------------------ random walk

    @Test
    void randomOperationsKeepTheLedgerAndLevelsInStep() {
        Random random = new Random(7);
        List<Reservation> active = new ArrayList<>();
        String[] skus = {"MUG", "LAMP"};
        String[] whs = {"WEST", "EAST"};
        for (int i = 0; i < 2000; i++) {
            String sku = skus[random.nextInt(2)];
            String wh = whs[random.nextInt(2)];
            try {
                switch (random.nextInt(6)) {
                    case 0 -> inv.receive(wh, sku, 1 + random.nextInt(5), "r");
                    case 1 -> active.add(inv.reserve("o" + i, lines(sku, 1 + random.nextInt(4)),
                            new Location(random.nextInt(100), 0)));
                    case 2 -> {
                        if (!active.isEmpty()) {
                            inv.commit(active.remove(random.nextInt(active.size())).id());
                        }
                    }
                    case 3 -> {
                        if (!active.isEmpty()) {
                            inv.release(active.remove(random.nextInt(active.size())).id());
                        }
                    }
                    case 4 -> inv.adjust(wh, sku, random.nextInt(5) - 3, "count");
                    default -> inv.transfer(sku, wh, wh.equals("WEST") ? "EAST" : "WEST", 1 + random.nextInt(3), "t");
                }
            } catch (InventoryException expected) {
                // refusals are part of the walk
            }
            if (i % 250 == 0) {
                clock.advance(Duration.ofMinutes(20));
                inv.expireReservations();
                active.removeIf(r -> r.status() != Reservation.Status.ACTIVE);
            }
        }
        assertLedgerConsistent(inv);
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void neverOversellsUnderConcurrentOrders() throws Exception {
        inv.receive("WEST", "MUG", 30, "load");
        inv.receive("EAST", "MUG", 20, "load");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String order = "o" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    Reservation r = inv.reserve(order, lines("MUG", 1), NEAR_WEST);
                    inv.commit(r.id());
                    ok.incrementAndGet();
                } catch (InventoryException soldOut) {
                    refused.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(50, ok.get());
        assertEquals(150, refused.get());
        assertEquals(0, inv.available("MUG"));
    }

    @Test
    void ordersLockingSkusInOppositeOrderDoNotDeadlock() throws Exception {
        inv.receive("WEST", "MUG", 1000, "load");
        inv.receive("WEST", "LAMP", 1000, "load");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            List<OrderLine> order = i % 2 == 0 ? lines("MUG", 1, "LAMP", 1) : lines("LAMP", 1, "MUG", 1);
            String id = "o" + i;
            futures.add(pool.submit(() -> inv.commit(inv.reserve(id, order, NEAR_WEST).id())));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(600, inv.available("MUG"));
        assertEquals(600, inv.available("LAMP"));
    }
}
