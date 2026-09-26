package com.lld.booking.food.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * <pre>
 * PLACED → ACCEPTED → PREPARING → READY_FOR_PICKUP → PICKED_UP → DELIVERED
 * PLACED → REJECTED (restaurant)        PLACED / ACCEPTED → CANCELLED (customer)
 * </pre>
 */
public enum OrderStatus {
    PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP, PICKED_UP, DELIVERED, REJECTED, CANCELLED;

    public Set<OrderStatus> next() {
        return switch (this) {
            case PLACED -> EnumSet.of(ACCEPTED, REJECTED, CANCELLED);
            case ACCEPTED -> EnumSet.of(PREPARING, CANCELLED);
            case PREPARING -> EnumSet.of(READY_FOR_PICKUP);
            case READY_FOR_PICKUP -> EnumSet.of(PICKED_UP);
            case PICKED_UP -> EnumSet.of(DELIVERED);
            case DELIVERED, REJECTED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean isFinal() {
        return next().isEmpty();
    }
}
