package com.lld.management.restaurant.model;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * One line a server punched in ("2 x burger, no onions"). Moves through the kitchen:
 *
 * <pre>
 * PLACED -> PREPARING -> READY -> SERVED
 * PLACED -> CANCELLED              (server, before the cook starts: free)
 * anything but CANCELLED -> VOIDED (manager comp: made but not charged)
 * </pre>
 */
public final class OrderItem {

    public enum Status {
        PLACED, PREPARING, READY, SERVED, CANCELLED, VOIDED;

        /** Still owed to the guest by the kitchen or the floor. */
        public boolean inProgress() {
            return this == PLACED || this == PREPARING || this == READY;
        }

        public boolean billable() {
            return this != CANCELLED && this != VOIDED;
        }

        Set<Status> next() {
            return switch (this) {
                case PLACED -> EnumSet.of(PREPARING, CANCELLED, VOIDED);
                case PREPARING -> EnumSet.of(READY, VOIDED);
                case READY -> EnumSet.of(SERVED, VOIDED);
                case SERVED -> EnumSet.of(VOIDED);
                case CANCELLED, VOIDED -> EnumSet.noneOf(Status.class);
            };
        }
    }

    private final String id;
    private final String tableId;
    private final MenuItem item;
    private final int quantity;
    private final String note;
    private final LocalDateTime orderedAt;
    private Status status = Status.PLACED;
    private String voidReason;

    public OrderItem(String id, String tableId, MenuItem item, int quantity, String note, LocalDateTime orderedAt) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        this.id = id;
        this.tableId = tableId;
        this.item = item;
        this.quantity = quantity;
        this.note = note == null ? "" : note;
        this.orderedAt = orderedAt;
    }

    public String id() {
        return id;
    }

    public String tableId() {
        return tableId;
    }

    public MenuItem item() {
        return item;
    }

    public int quantity() {
        return quantity;
    }

    public String note() {
        return note;
    }

    public LocalDateTime orderedAt() {
        return orderedAt;
    }

    public Status status() {
        return status;
    }

    public String voidReason() {
        return voidReason;
    }

    public long lineTotalCents() {
        return item.priceCents() * quantity;
    }

    /** @throws IllegalStateException if the kitchen workflow doesn't allow it */
    public void moveTo(Status target) {
        if (!status.next().contains(target)) {
            throw new IllegalStateException(id + " can't go from " + status + " to " + target);
        }
        status = target;
    }

    public void voidItem(String reason) {
        moveTo(Status.VOIDED);
        voidReason = reason;
    }

    @Override
    public String toString() {
        return id + " " + quantity + "x " + item.name() + (note.isEmpty() ? "" : " (" + note + ")") + " [" + status + "]";
    }
}
