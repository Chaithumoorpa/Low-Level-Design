package com.lld.management.inventory.model;

import java.time.Instant;
import java.util.List;

/**
 * Stock held for an order between "customer clicked buy" and "parcel left the building".
 * It either becomes a shipment ({@code COMMITTED}), is given back ({@code RELEASED}), or times out
 * ({@code EXPIRED}) so abandoned checkouts don't lock stock forever.
 */
public final class Reservation {

    public enum Status {
        ACTIVE, COMMITTED, RELEASED, EXPIRED
    }

    private final String id;
    private final String orderId;
    private final List<Allocation> allocations;
    private final Instant expiresAt;
    private volatile Status status = Status.ACTIVE;

    public Reservation(String id, String orderId, List<Allocation> allocations, Instant expiresAt) {
        this.id = id;
        this.orderId = orderId;
        this.allocations = List.copyOf(allocations);
        this.expiresAt = expiresAt;
    }

    public String id() {
        return id;
    }

    public String orderId() {
        return orderId;
    }

    public List<Allocation> allocations() {
        return allocations;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Status status() {
        return status;
    }

    /** Only the inventory service calls this, while holding the locks of every SKU involved. */
    public void setStatus(Status status) {
        this.status = status;
    }

    /** Number of distinct warehouses: each is a separate parcel. */
    public long shipments() {
        return allocations.stream().map(Allocation::warehouseId).distinct().count();
    }

    @Override
    public String toString() {
        return id + " for " + orderId + " " + allocations + " [" + status + "]";
    }
}
