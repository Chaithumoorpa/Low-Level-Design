package com.lld.states.coffee.payment;

import java.util.Objects;

/** A tapped card, charged through the payment gateway. */
public class CardPayment implements PaymentMethod {

    private final String cardToken;
    private final PaymentGateway gateway;

    public CardPayment(String cardToken, PaymentGateway gateway) {
        this.cardToken = Objects.requireNonNull(cardToken);
        this.gateway = Objects.requireNonNull(gateway);
    }

    @Override
    public String name() {
        return "card";
    }

    @Override
    public PaymentResult charge(int amount) {
        String transactionId = gateway.charge(cardToken, amount);
        return transactionId == null
                ? PaymentResult.declined("Card declined")
                : PaymentResult.approved(amount, 0, transactionId);
    }

    @Override
    public void refund(PaymentResult approvedCharge) {
        gateway.refund(approvedCharge.reference());
    }
}
