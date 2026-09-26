package com.lld.states.coffee.payment;

/**
 * Coins inserted by the customer. Change is returned as an amount; exact change-making from a
 * limited coin float is covered in depth in this repo's Vending Machine problem.
 */
public class CashPayment implements PaymentMethod {

    private final int inserted;
    private boolean refunded;

    public CashPayment(int insertedCents) {
        if (insertedCents <= 0) {
            throw new IllegalArgumentException("Insert some coins first");
        }
        this.inserted = insertedCents;
    }

    @Override
    public String name() {
        return "cash";
    }

    @Override
    public PaymentResult charge(int amount) {
        if (inserted < amount) {
            return PaymentResult.declined("Insert " + (amount - inserted) + "c more");
        }
        return PaymentResult.approved(amount, inserted - amount, "cash");
    }

    @Override
    public void refund(PaymentResult approvedCharge) {
        refunded = true;                           // the coins drop back into the coin return
    }

    public boolean wasRefunded() {
        return refunded;
    }
}
