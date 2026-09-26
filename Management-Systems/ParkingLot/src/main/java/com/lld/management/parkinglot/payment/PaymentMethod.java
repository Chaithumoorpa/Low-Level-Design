package com.lld.management.parkinglot.payment;

/**
 * Strategy: how the driver pays at the exit.
 *
 * @see CashPayment
 * @see CardPayment
 */
public interface PaymentMethod {

    /**
     * Takes the payment.
     *
     * @return a reference for the receipt
     * @throws com.lld.management.parkinglot.model.ParkingException if declined
     */
    String pay(long amountCents);
}
