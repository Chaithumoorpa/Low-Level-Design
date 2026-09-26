package com.lld.finance.splitwise.model;

/** "from should pay to this much" (a suggested or outstanding payment). */
public record Transfer(String fromId, String toId, long amountCents) {

    @Override
    public String toString() {
        return fromId + " -> " + toId + " " + Money.format(amountCents);
    }
}
