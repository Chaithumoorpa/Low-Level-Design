package com.lld.states.coffee.machine;

import com.lld.states.coffee.payment.PaymentResult;

/** A served drink, with what was paid. */
public record Cup(String description, int price, PaymentResult payment) {

    @Override
    public String toString() {
        return description + " (" + price + "c" + (payment.change() > 0 ? ", change " + payment.change() + "c" : "") + ")";
    }
}
