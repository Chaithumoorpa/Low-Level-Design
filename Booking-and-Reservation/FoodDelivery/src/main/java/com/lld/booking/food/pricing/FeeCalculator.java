package com.lld.booking.food.pricing;

import com.lld.booking.food.model.Bill;
import com.lld.booking.food.model.Promo;

/**
 * Delivery fee by distance, a small-order fee below a threshold, a promo on the item subtotal, and tax
 * on the discounted subtotal. All knobs are constructor parameters so markets can differ.
 */
public final class FeeCalculator {

    private final long baseDeliveryCents;
    private final double freeKm;
    private final long perExtraKmCents;
    private final long smallOrderThresholdCents;
    private final long smallOrderFeeCents;
    private final int taxPercent;

    public FeeCalculator(long baseDeliveryCents, double freeKm, long perExtraKmCents, long smallOrderThresholdCents,
                         long smallOrderFeeCents, int taxPercent) {
        this.baseDeliveryCents = baseDeliveryCents;
        this.freeKm = freeKm;
        this.perExtraKmCents = perExtraKmCents;
        this.smallOrderThresholdCents = smallOrderThresholdCents;
        this.smallOrderFeeCents = smallOrderFeeCents;
        this.taxPercent = taxPercent;
    }

    /** $1.99 + $0.50 per started km beyond 2 km; $1.50 under $15; 5% tax. */
    public static FeeCalculator standard() {
        return new FeeCalculator(199, 2.0, 50, 15_00, 150, 5);
    }

    public Bill bill(long subtotal, double distanceKm, Promo promo) {
        long extraKm = (long) Math.ceil(Math.max(0, distanceKm - freeKm));
        long delivery = baseDeliveryCents + extraKm * perExtraKmCents;
        long small = subtotal < smallOrderThresholdCents ? smallOrderFeeCents : 0;
        long discount = promo == null ? 0 : promo.discountFor(subtotal);
        long tax = Math.floorDiv((subtotal - discount) * taxPercent + 50, 100);
        return new Bill(subtotal, delivery, small, discount, tax, subtotal + delivery + small - discount + tax);
    }
}
