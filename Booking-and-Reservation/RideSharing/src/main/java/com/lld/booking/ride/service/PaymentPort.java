package com.lld.booking.ride.service;

/** Port to a payment provider; the booking logic only needs charge and refund. */
public interface PaymentPort {

    /** @return payment reference; throws {@link Declined} on failure */
    String charge(String userId, long amountCents, String description);

    void refund(String paymentReference, long amountCents);

    class Declined extends RuntimeException {
        public Declined(String message) {
            super(message);
        }
    }
}
