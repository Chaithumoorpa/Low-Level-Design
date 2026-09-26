package com.lld.booking.food.model;

/** "20% off, up to $5, on orders over $20." */
public record Promo(String code, int percentOff, long maxDiscountCents, long minSubtotalCents) {

    public long discountFor(long subtotal) {
        if (subtotal < minSubtotalCents) {
            return 0;
        }
        return Math.min(maxDiscountCents, subtotal * percentOff / 100);
    }
}
