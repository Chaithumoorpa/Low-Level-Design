package com.lld.management.inventory.model;

import java.time.Instant;

/** A request to a supplier to restock one SKU at one warehouse, raised automatically at the reorder point. */
public final class PurchaseOrder {

    public enum Status {
        OPEN, RECEIVED
    }

    private final String id;
    private final String warehouseId;
    private final String sku;
    private final int quantity;
    private final Instant raisedAt;
    private volatile Status status = Status.OPEN;

    public PurchaseOrder(String id, String warehouseId, String sku, int quantity, Instant raisedAt) {
        this.id = id;
        this.warehouseId = warehouseId;
        this.sku = sku;
        this.quantity = quantity;
        this.raisedAt = raisedAt;
    }

    public String id() {
        return id;
    }

    public String warehouseId() {
        return warehouseId;
    }

    public String sku() {
        return sku;
    }

    public int quantity() {
        return quantity;
    }

    public Instant raisedAt() {
        return raisedAt;
    }

    public Status status() {
        return status;
    }

    public void markReceived() {
        status = Status.RECEIVED;
    }

    @Override
    public String toString() {
        return id + ": " + quantity + "x" + sku + " to " + warehouseId + " [" + status + "]";
    }
}
