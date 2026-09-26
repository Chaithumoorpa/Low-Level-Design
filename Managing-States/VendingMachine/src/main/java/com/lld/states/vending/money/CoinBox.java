package com.lld.states.vending.money;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Coins held by the machine (the change float plus takings), denomination in cents → count.
 * Only the configured denominations are accepted.
 */
public class CoinBox {

    private final NavigableMap<Integer, Integer> coins = new TreeMap<>(Collections.reverseOrder());

    public CoinBox(int... denominations) {
        if (denominations.length == 0) {
            throw new IllegalArgumentException("At least one denomination is required");
        }
        for (int d : denominations) {
            if (d <= 0) {
                throw new IllegalArgumentException("Denominations must be positive");
            }
            coins.put(d, 0);
        }
    }

    public boolean accepts(int denomination) {
        return coins.containsKey(denomination);
    }

    public void add(Map<Integer, Integer> bundle) {
        bundle.forEach((d, n) -> {
            if (!accepts(d) || n < 0) {
                throw new IllegalArgumentException("Invalid coins: " + d + " x " + n);
            }
        });
        bundle.forEach((d, n) -> coins.merge(d, n, Integer::sum));
    }

    public void remove(Map<Integer, Integer> bundle) {
        bundle.forEach((d, n) -> {
            if (coins.getOrDefault(d, 0) < n) {
                throw new IllegalStateException("Not enough " + d + "c coins");
            }
        });
        bundle.forEach((d, n) -> coins.merge(d, -n, Integer::sum));
    }

    /** Empties the box (cash collection) and returns what was in it. */
    public Map<Integer, Integer> removeAll() {
        Map<Integer, Integer> taken = new HashMap<>();
        coins.forEach((d, n) -> {
            if (n > 0) {
                taken.put(d, n);
            }
        });
        coins.replaceAll((d, n) -> 0);
        return taken;
    }

    public int count(int denomination) {
        return coins.getOrDefault(denomination, 0);
    }

    public long total() {
        long sum = 0;
        for (Map.Entry<Integer, Integer> e : coins.entrySet()) {
            sum += (long) e.getKey() * e.getValue();
        }
        return sum;
    }

    /** Copy, largest denomination first. */
    public NavigableMap<Integer, Integer> snapshot() {
        return new TreeMap<>(coins);
    }

    /** A copy of this box with extra coins in it (used to plan change including the customer's coins). */
    public CoinBox plus(Map<Integer, Integer> extra) {
        CoinBox copy = new CoinBox(coins.keySet().stream().mapToInt(Integer::intValue).toArray());
        copy.add(coins);
        copy.add(extra);
        return copy;
    }
}
