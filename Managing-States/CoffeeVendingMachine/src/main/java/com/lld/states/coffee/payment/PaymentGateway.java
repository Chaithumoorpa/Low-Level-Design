package com.lld.states.coffee.payment;

/** The card processor (a remote service in reality). */
public interface PaymentGateway {

    /** @return a transaction id, or null if declined */
    String charge(String cardToken, int amount);

    void refund(String transactionId);
}
