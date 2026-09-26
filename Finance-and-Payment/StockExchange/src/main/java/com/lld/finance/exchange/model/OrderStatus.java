package com.lld.finance.exchange.model;

public enum OrderStatus {
    /** Resting on the book, nothing traded yet. */
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    /** By the trader, by IOC/FOK/market rules, or by self-trade prevention (see the reason). */
    CANCELLED,
    /** Never accepted: bad price, not enough cash or shares... */
    REJECTED;

    public boolean isLive() {
        return this == OPEN || this == PARTIALLY_FILLED;
    }
}
