package com.lld.states.coffee.payment;

/**
 * Strategy: how the customer pays. The machine only calls {@code charge} before brewing and
 * {@code refund} if brewing fails; it does not care whether money came from coins or a card.
 */
public interface PaymentMethod {

    String name();

    PaymentResult charge(int amount);

    /** Gives the money of an approved charge back (coins out of the slot, or a card refund). */
    void refund(PaymentResult approvedCharge);
}
