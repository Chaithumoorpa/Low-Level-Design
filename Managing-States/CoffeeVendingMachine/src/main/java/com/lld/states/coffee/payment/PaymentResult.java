package com.lld.states.coffee.payment;

/**
 * Outcome of a charge.
 *
 * @param reference id needed to refund (card transaction id, or "cash")
 * @param change    coins handed back for a cash payment (0 for cards)
 */
public record PaymentResult(boolean approved, int charged, int change, String reference, String message) {

    public static PaymentResult approved(int charged, int change, String reference) {
        return new PaymentResult(true, charged, change, reference, "approved");
    }

    public static PaymentResult declined(String message) {
        return new PaymentResult(false, 0, 0, null, message);
    }
}
