package com.lld.states.vending.model;

import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

/** Helpers for amounts in cents and coin bundles (denomination in cents → count). */
public final class Money {

    private Money() {
    }

    public static String format(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }

    public static long total(Map<Integer, Integer> coins) {
        long sum = 0;
        for (Map.Entry<Integer, Integer> e : coins.entrySet()) {
            sum += (long) e.getKey() * e.getValue();
        }
        return sum;
    }

    /** "2 x 25c, 1 x 10c" style text, largest coin first. */
    public static String describe(Map<Integer, Integer> coins) {
        if (coins.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        Map<Integer, Integer> sorted = new TreeMap<>(Collections.reverseOrder());
        sorted.putAll(coins);
        sorted.forEach((d, n) -> sb.append(sb.length() == 0 ? "" : ", ").append(n).append(" x ")
                .append(d >= 100 ? format(d) : d + "c"));
        return sb.toString();
    }
}
