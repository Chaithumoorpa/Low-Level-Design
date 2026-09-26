package com.lld.booking.food.model;

/** Itemised charges, all in cents. total = subtotal + delivery + smallOrder + tax − discount. */
public record Bill(long subtotal, long deliveryFee, long smallOrderFee, long discount, long tax, long total) {

    @Override
    public String toString() {
        return String.format("items %s + delivery %s + small-order %s - promo %s + tax %s = %s", m(subtotal),
                m(deliveryFee), m(smallOrderFee), m(discount), m(tax), m(total));
    }

    private static String m(long c) {
        return String.format("$%d.%02d", c / 100, c % 100);
    }
}
