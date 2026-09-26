package com.lld.management.parkinglot.payment;

import com.lld.management.parkinglot.model.ParkingException;

/** Notes and coins at the pay station; change is reported on the receipt reference. */
public class CashPayment implements PaymentMethod {

    private final long tenderedCents;

    public CashPayment(long tenderedCents) {
        this.tenderedCents = tenderedCents;
    }

    @Override
    public String pay(long amountCents) {
        if (tenderedCents < amountCents) {
            throw new ParkingException("Insert " + (amountCents - tenderedCents) + "c more");
        }
        return "CASH, change " + (tenderedCents - amountCents) + "c";
    }
}
