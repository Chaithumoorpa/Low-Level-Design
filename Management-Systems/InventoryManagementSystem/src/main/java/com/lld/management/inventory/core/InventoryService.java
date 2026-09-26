package com.lld.management.inventory.core;

import com.lld.management.inventory.fulfillment.FulfillmentStrategy;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Facade for stock across all warehouses.
 *
 * <p><b>Consistency:</b> every quantity lives in a {@code Bin} (one SKU in one warehouse) and every
 * change to a bin is written to the append-only ledger in the same critical section, so
 * {@code onHand = sum of ledger onHand deltas} always holds and {@code 0 <= reserved <= onHand}.
 *
 * <p><b>Concurrency:</b> one lock per SKU (lock striping). An order touching several SKUs locks them
 * all, <b>in sorted SKU order</b>, so two orders can never deadlock, and then reserves all lines or
 * none. Orders for unrelated SKUs run in parallel. Listener events fire after the locks are released.
 */
public final class InventoryService {

    /** Mutable quantities of one SKU in one warehouse. Guarded by that SKU's lock. */
    private static final class Bin {
        int onHand;
        int reserved;

        int available() {
            return onHand - reserved;
        }
    }

    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final Map<String, Warehouse> warehouses = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Bin>> stock = new ConcurrentHashMap<>();       // sku -> warehouse -> bin
    private final Map<String, ReentrantLock> skuLocks = new ConcurrentHashMap<>();
    private final Map<String, ReorderPolicy> policies = new ConcurrentHashMap<>();       // "wh|sku"
    private final Map<String, PurchaseOrder> openPurchaseOrderByBin = new ConcurrentHashMap<>();
    private final Map<String, PurchaseOrder> purchaseOrders = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservationByOrder = new ConcurrentHashMap<>();
    private final List<StockMovement> ledger = Collections.synchronizedList(new ArrayList<>());
    private final List<InventoryListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong movementSeq = new AtomicLong();
    private final AtomicLong reservationSeq = new AtomicLong();
    private final AtomicLong purchaseOrderSeq = new AtomicLong();

    private final FulfillmentStrategy fulfillment;
    private final Duration reservationTtl;
    private final Clock clock;

    public InventoryService(FulfillmentStrategy fulfillment, Duration reservationTtl, Clock clock) {
        this.fulfillment = Objects.requireNonNull(fulfillment);
        this.reservationTtl = Objects.requireNonNull(reservationTtl);
        this.clock = Objects.requireNonNull(clock);
    }

    public void addListener(InventoryListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ setup

    public void addProduct(Product product) {
        if (products.putIfAbsent(product.sku(), product) != null) {
            throw new InventoryException("Duplicate SKU " + product.sku());
        }
    }

    public void addWarehouse(Warehouse warehouse) {
        if (warehouses.putIfAbsent(warehouse.id(), warehouse) != null) {
            throw new InventoryException("Duplicate warehouse " + warehouse.id());
        }
    }

    /** Sets the rule and applies it at once, so a bin that is already low gets a purchase order. */
    public void setReorderPolicy(String warehouseId, String sku, ReorderPolicy policy) {
        requireWarehouse(warehouseId);
        requireProduct(sku);
        policies.put(key(warehouseId, sku), policy);
        List<Runnable> events = new ArrayList<>();
        lock(List.of(sku));
        try {
            raisePurchaseOrderIfLow(warehouseId, sku, bin(warehouseId, sku), events);
        } finally {
            unlock(List.of(sku));
        }
        events.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ inbound

    /** Goods arrived at the dock (a delivery without a purchase order, a customer return...). */
    public void receive(String warehouseId, String sku, int quantity, String reference) {
        requirePositive(quantity);
        requireWarehouse(warehouseId);
        requireProduct(sku);
        lock(List.of(sku));
        try {
            Bin bin = bin(warehouseId, sku);
            bin.onHand += quantity;
            record(StockMovement.Type.RECEIVE, warehouseId, sku, quantity, 0, reference);
        } finally {
            unlock(List.of(sku));
        }
    }

    /** The supplier delivered a purchase order: stock goes up and the PO is closed. */
    public void receivePurchaseOrder(String purchaseOrderId) {
        PurchaseOrder po = purchaseOrders.get(purchaseOrderId);
        if (po == null) {
            throw new InventoryException("No purchase order " + purchaseOrderId);
        }
        List<Runnable> events = new ArrayList<>();
        lock(List.of(po.sku()));
        try {
            if (po.status() != PurchaseOrder.Status.OPEN) {
                throw new InventoryException(purchaseOrderId + " was already received");
            }
            Bin bin = bin(po.warehouseId(), po.sku());
            bin.onHand += po.quantity();
            record(StockMovement.Type.RECEIVE, po.warehouseId(), po.sku(), po.quantity(), 0, po.id());
            po.markReceived();
            openPurchaseOrderByBin.remove(key(po.warehouseId(), po.sku()));
            raisePurchaseOrderIfLow(po.warehouseId(), po.sku(), bin, events);    // still low? order again
        } finally {
            unlock(List.of(po.sku()));
        }
        events.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ corrections & moves

    /**
     * Cycle count or damage: changes physical stock. Refused if it would leave less on the shelf than
     * is already promised to orders.
     */
    public void adjust(String warehouseId, String sku, int delta, String reason) {
        requireWarehouse(warehouseId);
        requireProduct(sku);
        List<Runnable> events = new ArrayList<>();
        lock(List.of(sku));
        try {
            Bin bin = bin(warehouseId, sku);
            if (bin.onHand + delta < bin.reserved) {
                throw new InventoryException("Adjusting " + sku + "@" + warehouseId + " by " + delta + " would leave "
                        + (bin.onHand + delta) + " on hand with " + bin.reserved + " reserved");
            }
            bin.onHand += delta;
            record(StockMovement.Type.ADJUST, warehouseId, sku, delta, 0, reason);
            if (delta < 0) {
                afterAvailableDropped(warehouseId, sku, bin, events);
            }
        } finally {
            unlock(List.of(sku));
        }
        events.forEach(Runnable::run);
    }

    /** Moves available stock between warehouses (instantly here; see README for in-transit stock). */
    public void transfer(String sku, String fromId, String toId, int quantity, String reference) {
        requirePositive(quantity);
        requireWarehouse(fromId);
        requireWarehouse(toId);
        requireProduct(sku);
        if (fromId.equals(toId)) {
            throw new InventoryException("Transfer to the same warehouse");
        }
        List<Runnable> events = new ArrayList<>();
        lock(List.of(sku));
        try {
            Bin from = bin(fromId, sku);
            if (from.available() < quantity) {
                throw new InventoryException("Only " + from.available() + " " + sku + " available at " + fromId);
            }
            from.onHand -= quantity;
            bin(toId, sku).onHand += quantity;
            record(StockMovement.Type.TRANSFER_OUT, fromId, sku, -quantity, 0, reference);
            record(StockMovement.Type.TRANSFER_IN, toId, sku, quantity, 0, reference);
            afterAvailableDropped(fromId, sku, from, events);
        } finally {
            unlock(List.of(sku));
        }
        events.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ orders

    /**
     * Holds stock for an order: all lines or nothing. Safe to retry: a second call with the same
     * order id returns the existing reservation instead of reserving twice.
     */
    public Reservation reserve(String orderId, List<OrderLine> lines, Location shipTo) {
        Objects.requireNonNull(orderId);
        if (lines.isEmpty()) {
            throw new InventoryException("Order " + orderId + " has no lines");
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (OrderLine l : lines) {
            requireProduct(l.sku());
            merged.merge(l.sku(), l.quantity(), Integer::sum);
        }
        List<OrderLine> wanted = merged.entrySet().stream().map(e -> new OrderLine(e.getKey(), e.getValue())).toList();

        List<Runnable> events = new ArrayList<>();
        Reservation reservation;
        lock(merged.keySet());
        try {
            Reservation existing = reservationByOrder.get(orderId);
            if (existing != null && (existing.status() == Reservation.Status.ACTIVE
                    || existing.status() == Reservation.Status.COMMITTED)) {
                return existing;                                           // idempotent retry
            }
            Map<String, Map<String, Integer>> available = new HashMap<>();
            for (String sku : merged.keySet()) {
                Map<String, Integer> perWarehouse = new HashMap<>();
                stock.getOrDefault(sku, Map.of()).forEach((wh, bin) -> perWarehouse.put(wh, bin.available()));
                available.put(sku, perWarehouse);
            }
            List<Allocation> plan = fulfillment.plan(wanted, available, List.copyOf(warehouses.values()), shipTo)
                    .orElseThrow(() -> new InventoryException(explainShortage(orderId, wanted, available)));

            reservation = new Reservation("RES-" + reservationSeq.incrementAndGet(), orderId, plan,
                    clock.instant().plus(reservationTtl));
            for (Allocation a : plan) {
                Bin bin = bin(a.warehouseId(), a.sku());
                if (bin.available() < a.quantity()) {                     // guards against a faulty strategy
                    throw new IllegalStateException("Strategy over-allocated " + a);
                }
            }
            for (Allocation a : plan) {
                Bin bin = bin(a.warehouseId(), a.sku());
                bin.reserved += a.quantity();
                record(StockMovement.Type.RESERVE, a.warehouseId(), a.sku(), 0, a.quantity(), orderId);
                afterAvailableDropped(a.warehouseId(), a.sku(), bin, events);
            }
            reservations.put(reservation.id(), reservation);
            reservationByOrder.put(orderId, reservation);
        } finally {
            unlock(merged.keySet());
        }
        Reservation done = reservation;
        listeners.forEach(l -> l.onReserved(done));
        events.forEach(Runnable::run);
        return reservation;
    }

    /** The order shipped: reserved stock leaves the building. Refused (and released) if it expired. */
    public void commit(String reservationId) {
        Reservation r = reservation(reservationId);
        Collection<String> skus = skusOf(r);
        boolean expired = false;
        lock(skus);
        try {
            requireActive(r);
            if (isExpired(r)) {
                releaseLocked(r, Reservation.Status.EXPIRED);
                expired = true;
            } else {
                for (Allocation a : r.allocations()) {
                    Bin bin = bin(a.warehouseId(), a.sku());
                    bin.reserved -= a.quantity();
                    bin.onHand -= a.quantity();
                    record(StockMovement.Type.SHIP, a.warehouseId(), a.sku(), -a.quantity(), -a.quantity(), r.orderId());
                }
                r.setStatus(Reservation.Status.COMMITTED);
            }
        } finally {
            unlock(skus);
        }
        if (expired) {
            listeners.forEach(l -> l.onReleased(r));
            throw new InventoryException(reservationId + " expired at " + r.expiresAt() + "; stock was released");
        }
        listeners.forEach(l -> l.onShipped(r));
    }

    /** Order cancelled or payment failed: give the stock back. Releasing twice is harmless. */
    public void release(String reservationId) {
        Reservation r = reservation(reservationId);
        Collection<String> skus = skusOf(r);
        lock(skus);
        try {
            if (r.status() == Reservation.Status.RELEASED || r.status() == Reservation.Status.EXPIRED) {
                return;
            }
            requireActive(r);
            releaseLocked(r, Reservation.Status.RELEASED);
        } finally {
            unlock(skus);
        }
        listeners.forEach(l -> l.onReleased(r));
    }

    /** Run periodically: frees stock held by abandoned checkouts. @return how many expired */
    public int expireReservations() {
        int count = 0;
        for (Reservation r : reservations.values()) {
            if (r.status() != Reservation.Status.ACTIVE || !isExpired(r)) {
                continue;
            }
            Collection<String> skus = skusOf(r);
            boolean expiredNow = false;
            lock(skus);
            try {
                if (r.status() == Reservation.Status.ACTIVE) {             // re-check under the lock
                    releaseLocked(r, Reservation.Status.EXPIRED);
                    expiredNow = true;
                }
            } finally {
                unlock(skus);
            }
            if (expiredNow) {
                count++;
                listeners.forEach(l -> l.onReleased(r));
            }
        }
        return count;
    }

    private void releaseLocked(Reservation r, Reservation.Status finalStatus) {
        for (Allocation a : r.allocations()) {
            bin(a.warehouseId(), a.sku()).reserved -= a.quantity();
            record(StockMovement.Type.RELEASE, a.warehouseId(), a.sku(), 0, -a.quantity(),
                    r.orderId() + (finalStatus == Reservation.Status.EXPIRED ? " (expired)" : ""));
        }
        r.setStatus(finalStatus);
    }

    // ------------------------------------------------------------------ queries

    public StockLevel stockLevel(String warehouseId, String sku) {
        requireWarehouse(warehouseId);
        requireProduct(sku);
        lock(List.of(sku));
        try {
            Bin bin = stock.getOrDefault(sku, Map.of()).get(warehouseId);
            return bin == null ? new StockLevel(warehouseId, sku, 0, 0)
                    : new StockLevel(warehouseId, sku, bin.onHand, bin.reserved);
        } finally {
            unlock(List.of(sku));
        }
    }

    /** Sellable quantity across the whole network (what the storefront shows). */
    public int available(String sku) {
        requireProduct(sku);
        lock(List.of(sku));
        try {
            return stock.getOrDefault(sku, Map.of()).values().stream().mapToInt(Bin::available).sum();
        } finally {
            unlock(List.of(sku));
        }
    }

    /** Value of everything on the shelves, at unit cost. */
    public long stockValueCents() {
        long total = 0;
        for (Product p : products.values()) {
            lock(List.of(p.sku()));
            try {
                total += stock.getOrDefault(p.sku(), Map.of()).values().stream().mapToLong(b -> b.onHand).sum()
                        * p.unitCostCents();
            } finally {
                unlock(List.of(p.sku()));
            }
        }
        return total;
    }

    public Reservation reservation(String reservationId) {
        Reservation r = reservations.get(reservationId);
        if (r == null) {
            throw new InventoryException("No reservation " + reservationId);
        }
        return r;
    }

    public List<StockMovement> ledger() {
        synchronized (ledger) {
            return List.copyOf(ledger);
        }
    }

    public List<PurchaseOrder> openPurchaseOrders() {
        return openPurchaseOrderByBin.values().stream()
                .sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }

    public List<Warehouse> warehouses() {
        return warehouses.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }

    // ------------------------------------------------------------------ internals

    /** Called with the SKU lock held after available stock went down. */
    private void afterAvailableDropped(String warehouseId, String sku, Bin bin, List<Runnable> events) {
        if (bin.available() == 0) {
            events.add(() -> listeners.forEach(l -> l.onOutOfStock(warehouseId, sku)));
        }
        raisePurchaseOrderIfLow(warehouseId, sku, bin, events);
    }

    /** At most one open purchase order per bin, so a busy day doesn't order the same pallet 50 times. */
    private void raisePurchaseOrderIfLow(String warehouseId, String sku, Bin bin, List<Runnable> events) {
        String key = key(warehouseId, sku);
        ReorderPolicy policy = policies.get(key);
        if (policy == null || bin.available() > policy.reorderPoint() || openPurchaseOrderByBin.containsKey(key)) {
            return;
        }
        StockLevel level = new StockLevel(warehouseId, sku, bin.onHand, bin.reserved);
        int quantity = policy.strategy().quantityToOrder(level);
        if (quantity <= 0) {
            return;
        }
        PurchaseOrder po = new PurchaseOrder("PO-" + purchaseOrderSeq.incrementAndGet(), warehouseId, sku, quantity,
                clock.instant());
        purchaseOrders.put(po.id(), po);
        openPurchaseOrderByBin.put(key, po);
        events.add(() -> listeners.forEach(l -> l.onLowStock(level, po)));
    }

    private void record(StockMovement.Type type, String warehouseId, String sku, int onHandDelta, int reservedDelta,
                        String reference) {
        ledger.add(new StockMovement(movementSeq.incrementAndGet(), clock.instant(), type, warehouseId, sku,
                onHandDelta, reservedDelta, reference));
    }

    private Bin bin(String warehouseId, String sku) {
        return stock.computeIfAbsent(sku, k -> new ConcurrentHashMap<>()).computeIfAbsent(warehouseId, k -> new Bin());
    }

    /** Locks in sorted order: every thread takes shared locks in the same sequence, so no deadlock. */
    private void lock(Collection<String> skus) {
        for (String sku : new TreeSet<>(skus)) {
            skuLocks.computeIfAbsent(sku, k -> new ReentrantLock()).lock();
        }
    }

    private void unlock(Collection<String> skus) {
        for (String sku : new TreeSet<>(skus).descendingSet()) {
            skuLocks.get(sku).unlock();
        }
    }

    private static Collection<String> skusOf(Reservation r) {
        return r.allocations().stream().map(Allocation::sku).distinct().toList();
    }

    private boolean isExpired(Reservation r) {
        return !clock.instant().isBefore(r.expiresAt());
    }

    private static void requireActive(Reservation r) {
        if (r.status() != Reservation.Status.ACTIVE) {
            throw new InventoryException(r.id() + " is " + r.status());
        }
    }

    private String explainShortage(String orderId, List<OrderLine> wanted, Map<String, Map<String, Integer>> available) {
        List<String> shortages = new ArrayList<>();
        for (OrderLine l : wanted) {
            int total = available.get(l.sku()).values().stream().mapToInt(Integer::intValue).sum();
            if (total < l.quantity()) {
                shortages.add(l.sku() + " needs " + l.quantity() + ", " + total + " available");
            }
        }
        return shortages.isEmpty()
                ? "No single warehouse can ship order " + orderId + " (splitting is not allowed)"
                : "Not enough stock for order " + orderId + ": " + String.join("; ", shortages);
    }

    private void requireProduct(String sku) {
        if (!products.containsKey(sku)) {
            throw new InventoryException("Unknown SKU " + sku);
        }
    }

    private void requireWarehouse(String warehouseId) {
        if (!warehouses.containsKey(warehouseId)) {
            throw new InventoryException("Unknown warehouse " + warehouseId);
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new InventoryException("Quantity must be positive: " + quantity);
        }
    }

    private static String key(String warehouseId, String sku) {
        return warehouseId + "|" + sku;
    }
}
