package com.lld.finance.splitwise.model;

/** Amounts are {@code long} cents everywhere: no floating point, so every split adds up exactly. */
public final class Money {

    private Money() {
    }

    public static String format(long cents) {
        return (cents < 0 ? "-" : "") + String.format("$%d.%02d", Math.abs(cents) / 100, Math.abs(cents) % 100);
    }
}
