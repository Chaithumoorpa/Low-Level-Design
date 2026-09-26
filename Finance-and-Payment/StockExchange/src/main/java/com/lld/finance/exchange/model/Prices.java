package com.lld.finance.exchange.model;

/** Prices and cash are {@code long} cents: exact, fast to compare, safe as map keys. */
public final class Prices {

    private Prices() {
    }

    public static String format(long cents) {
        return (cents < 0 ? "-" : "") + String.format("%d.%02d", Math.abs(cents) / 100, Math.abs(cents) % 100);
    }
}
