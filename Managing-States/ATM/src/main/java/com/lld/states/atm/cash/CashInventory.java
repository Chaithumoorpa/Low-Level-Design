package com.lld.states.atm.cash;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Notes held in the machine's cassettes: denomination → count, largest denomination first.
 * Only the denominations configured at construction are accepted.
 */
public class CashInventory {

    private final NavigableMap<Integer, Integer> notes = new TreeMap<>(Collections.reverseOrder());

    public CashInventory(int... denominations) {
        if (denominations.length == 0) {
            throw new IllegalArgumentException("At least one denomination is required");
        }
        for (int d : denominations) {
            if (d <= 0) {
                throw new IllegalArgumentException("Denominations must be positive");
            }
            notes.put(d, 0);
        }
    }

    public void add(Map<Integer, Integer> bundle) {
        validate(bundle);
        bundle.forEach((d, n) -> notes.merge(d, n, Integer::sum));
    }

    public void remove(Map<Integer, Integer> bundle) {
        validate(bundle);
        bundle.forEach((d, n) -> {
            if (notes.get(d) < n) {
                throw new IllegalStateException("Not enough " + d + " notes");
            }
        });
        bundle.forEach((d, n) -> notes.merge(d, -n, Integer::sum));
    }

    public boolean accepts(int denomination) {
        return notes.containsKey(denomination);
    }

    public int count(int denomination) {
        return notes.getOrDefault(denomination, 0);
    }

    public long total() {
        long sum = 0;
        for (Map.Entry<Integer, Integer> e : notes.entrySet()) {
            sum += (long) e.getKey() * e.getValue();
        }
        return sum;
    }

    public int smallestDenomination() {
        return notes.lastKey();
    }

    /** Read-only view, largest denomination first. */
    public NavigableMap<Integer, Integer> snapshot() {
        return Collections.unmodifiableNavigableMap(new TreeMap<>(notes));
    }

    private void validate(Map<Integer, Integer> bundle) {
        bundle.forEach((d, n) -> {
            if (!notes.containsKey(d)) {
                throw new IllegalArgumentException("Denomination " + d + " is not accepted");
            }
            if (n < 0) {
                throw new IllegalArgumentException("Negative note count");
            }
        });
    }

    @Override
    public String toString() {
        return notes + " = " + total();
    }
}
