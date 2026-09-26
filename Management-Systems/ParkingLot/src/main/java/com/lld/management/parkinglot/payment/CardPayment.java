package com.lld.management.parkinglot.payment;

import com.lld.management.parkinglot.model.ParkingException;

import java.util.function.BiPredicate;

/** A card at the exit terminal, authorised by a card processor (a remote service in reality). */
public class CardPayment implements PaymentMethod {

    private final String cardToken;
    private final BiPredicate<String, Long> processor;

    /** @param processor returns true if the charge was approved */
    public CardPayment(String cardToken, BiPredicate<String, Long> processor) {
        this.cardToken = cardToken;
        this.processor = processor;
    }

    @Override
    public String pay(long amountCents) {
        if (!processor.test(cardToken, amountCents)) {
            throw new ParkingException("Card declined");
        }
        return "CARD " + cardToken;
    }
}
