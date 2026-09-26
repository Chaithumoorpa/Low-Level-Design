package com.lld.social.learning.payment;

/**
 * Port to a payment provider (e.g. the Payment Gateway in the Finance folder). The platform only needs
 * "charge" and "refund"; which provider sits behind it is an adapter detail.
 */
public interface PaymentPort {

    /** @return a payment reference; throws {@link PaymentDeclined} if the charge fails */
    String charge(String studentId, long amountCents, String description);

    void refund(String paymentReference, long amountCents);

    class PaymentDeclined extends RuntimeException {
        public PaymentDeclined(String message) {
            super(message);
        }
    }
}
