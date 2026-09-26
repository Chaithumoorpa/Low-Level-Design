package com.lld.states.vending.money;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which coins to give back as change, using only coins the machine actually has.
 *
 * <p>Greedy ("largest coin first") is wrong with a limited float: 30c change with one 25c and three
 * 10c fails greedily (25 + ? = no 5c) although 3 x 10c works. So this uses <b>bounded coin change</b>
 * dynamic programming and returns the fewest coins, or empty if change cannot be made.
 *
 * <pre>
 *   best[i][a] = fewest coins making a with the first i denominations
 *              = min over c ≤ count_i of  best[i-1][a - c·d_i] + c
 * </pre>
 */
public final class ChangeMaker {

    private static final int INF = Integer.MAX_VALUE / 2;

    private ChangeMaker() {
    }

    public static Optional<Map<Integer, Integer>> makeChange(long amount, CoinBox box) {
        if (amount == 0) {
            return Optional.of(Map.of());
        }
        Map<Integer, Integer> stock = box.snapshot();
        int unit = 0;
        for (int d : stock.keySet()) {
            unit = gcd(unit, d);
        }
        if (amount < 0 || amount % unit != 0 || amount > box.total()) {
            return Optional.empty();
        }
        int target = (int) (amount / unit);
        List<Integer> denominations = new ArrayList<>(stock.keySet());
        int n = denominations.size();
        int[][] best = new int[n + 1][target + 1];
        int[][] used = new int[n + 1][target + 1];
        Arrays.fill(best[0], INF);
        best[0][0] = 0;

        for (int i = 1; i <= n; i++) {
            int size = denominations.get(i - 1) / unit;
            int available = stock.get(denominations.get(i - 1));
            for (int a = 0; a <= target; a++) {
                best[i][a] = INF;
                for (int c = 0; c <= Math.min(available, a / size); c++) {
                    int candidate = best[i - 1][a - c * size] + c;
                    if (candidate < best[i][a]) {
                        best[i][a] = candidate;
                        used[i][a] = c;
                    }
                }
            }
        }
        if (best[n][target] >= INF) {
            return Optional.empty();
        }
        int[] counts = new int[n];
        int a = target;
        for (int i = n; i >= 1; i--) {
            counts[i - 1] = used[i][a];
            a -= counts[i - 1] * (denominations.get(i - 1) / unit);
        }
        Map<Integer, Integer> change = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (counts[i] > 0) {
                change.put(denominations.get(i), counts[i]);
            }
        }
        return Optional.of(change);
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
