package com.lld.booking.food.dispatch;

import com.lld.booking.food.model.DeliveryPartner;
import com.lld.booking.food.model.Order;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks a courier for an order from the idle couriers who haven't declined it (Strategy).
 */
public interface AssignmentStrategy {

    Optional<DeliveryPartner> choose(Order order, List<DeliveryPartner> candidates);

    /** Closest to the restaurant; ties go to the better-rated courier, then by id. */
    static AssignmentStrategy nearest() {
        return (order, candidates) -> candidates.stream().min(
                Comparator.comparingDouble((DeliveryPartner p) -> p.location().distanceTo(order.restaurant().location()))
                        .thenComparing(Comparator.comparingDouble((DeliveryPartner p) -> p.rating().average()).reversed())
                        .thenComparing(DeliveryPartner::id));
    }

    /** Best-rated courier within {@code radiusKm} of the restaurant (nearest as tie-break). */
    static AssignmentStrategy bestRatedWithin(double radiusKm) {
        return (order, candidates) -> candidates.stream()
                .filter(p -> p.location().distanceTo(order.restaurant().location()) <= radiusKm)
                .min(Comparator.comparingDouble((DeliveryPartner p) -> p.rating().average()).reversed()
                        .thenComparingDouble(p -> p.location().distanceTo(order.restaurant().location()))
                        .thenComparing(DeliveryPartner::id));
    }
}
