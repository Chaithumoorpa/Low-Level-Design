package com.lld.finance.splitwise.split;

import com.lld.finance.splitwise.model.SplitwiseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How an expense is divided (Strategy). Every variant returns exact cents per person that add up to
 * the total. Leftover cents from rounding are handed out by the <b>largest remainder</b> rule (ties go to
 * whoever is listed first), so $100 split three ways is 33.34 / 33.33 / 33.33, never 99.99.
 */
public sealed interface Split {

    /** @return participant -> cents owed, summing exactly to {@code totalCents} */
    Map<String, Long> owed(long totalCents);

    String label();

    // ------------------------------------------------------------------ factories

    static Split equal(String... participants) {
        return new Equal(List.of(participants));
    }

    static Split exact(Map<String, Long> amounts) {
        return new Exact(new LinkedHashMap<>(amounts));
    }

    /** Percentages in basis points (100% = 10,000) so 33.33% is representable exactly. */
    static Split percent(Map<String, Integer> basisPoints) {
        return new Percent(new LinkedHashMap<>(basisPoints));
    }

    static Split shares(Map<String, Integer> shares) {
        return new Shares(new LinkedHashMap<>(shares));
    }

    // ------------------------------------------------------------------ variants

    record Equal(List<String> participants) implements Split {
        public Equal {
            if (participants.isEmpty() || participants.stream().distinct().count() != participants.size()) {
                throw new SplitwiseException("Equal split needs distinct participants");
            }
        }

        @Override
        public Map<String, Long> owed(long totalCents) {
            Map<String, Long> weights = new LinkedHashMap<>();
            participants.forEach(p -> weights.put(p, 1L));
            return allocate(totalCents, weights);
        }

        @Override
        public String label() {
            return "equally";
        }
    }

    record Exact(Map<String, Long> amounts) implements Split {
        @Override
        public Map<String, Long> owed(long totalCents) {
            long sum = amounts.values().stream().mapToLong(Long::longValue).sum();
            if (amounts.values().stream().anyMatch(a -> a < 0)) {
                throw new SplitwiseException("Exact amounts can't be negative");
            }
            if (sum != totalCents) {
                throw new SplitwiseException("Exact amounts add up to " + sum + "c, not " + totalCents + "c");
            }
            return new LinkedHashMap<>(amounts);
        }

        @Override
        public String label() {
            return "by exact amounts";
        }
    }

    record Percent(Map<String, Integer> basisPoints) implements Split {
        @Override
        public Map<String, Long> owed(long totalCents) {
            int sum = basisPoints.values().stream().mapToInt(Integer::intValue).sum();
            if (sum != 10_000 || basisPoints.values().stream().anyMatch(b -> b < 0)) {
                throw new SplitwiseException("Percentages must be non-negative and add up to 100% (got "
                        + sum / 100.0 + "%)");
            }
            Map<String, Long> weights = new LinkedHashMap<>();
            basisPoints.forEach((u, b) -> weights.put(u, (long) b));
            return allocate(totalCents, weights);
        }

        @Override
        public String label() {
            return "by percentage";
        }
    }

    record Shares(Map<String, Integer> shares) implements Split {
        @Override
        public Map<String, Long> owed(long totalCents) {
            if (shares.isEmpty() || shares.values().stream().anyMatch(s -> s <= 0)) {
                throw new SplitwiseException("Shares must be positive");
            }
            Map<String, Long> weights = new LinkedHashMap<>();
            shares.forEach((u, s) -> weights.put(u, (long) s));
            return allocate(totalCents, weights);
        }

        @Override
        public String label() {
            return "by shares";
        }
    }

    /** Largest-remainder allocation of {@code total} proportionally to {@code weights}. */
    static Map<String, Long> allocate(long total, Map<String, Long> weights) {
        long weightSum = weights.values().stream().mapToLong(Long::longValue).sum();
        if (weightSum <= 0) {
            throw new SplitwiseException("Nothing to split by");
        }
        Map<String, Long> out = new LinkedHashMap<>();
        List<String> order = new ArrayList<>(weights.keySet());
        Map<String, Long> remainders = new LinkedHashMap<>();
        long given = 0;
        for (String u : order) {
            long exact = total * weights.get(u);
            out.put(u, exact / weightSum);
            remainders.put(u, exact % weightSum);
            given += exact / weightSum;
        }
        List<String> byRemainder = new ArrayList<>(order);
        byRemainder.sort(Comparator.comparing((String u) -> remainders.get(u)).reversed()
                .thenComparingInt(order::indexOf));
        for (int i = 0; given < total; i++, given++) {
            String u = byRemainder.get(i);
            out.put(u, out.get(u) + 1);
        }
        return out;
    }
}
