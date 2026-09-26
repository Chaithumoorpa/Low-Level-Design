package com.lld.states.atm.cash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Always finds a way to pay if one exists, using the fewest notes: <b>bounded coin change</b> by
 * dynamic programming over (denominations considered, amount).
 *
 * <pre>
 *   best[i][a] = fewest notes making amount a with the first i denominations
 *              = min over c in 0..min(count_i, a / d_i) of  best[i-1][a - c·d_i] + c
 * </pre>
 * The chosen c is stored per cell, so the plan is read back by walking the table in reverse.
 * Amounts are divided by the gcd of the denominations first (100 for 100/200/500/2000), so a
 * 20,000 withdrawal needs a 5 x 201 table. The ATM's per-transaction limit keeps it small.
 */
public class OptimalDispenseStrategy implements DispenseStrategy {

    private static final int INF = Integer.MAX_VALUE / 2;

    @Override
    public Optional<Map<Integer, Integer>> plan(long amount, CashInventory inventory) {
        Map<Integer, Integer> stock = inventory.snapshot();          // largest first
        int unit = 0;
        for (int d : stock.keySet()) {
            unit = gcd(unit, d);
        }
        if (amount <= 0 || amount % unit != 0 || amount > inventory.total()) {
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
                int maxNotes = Math.min(available, a / size);
                for (int c = 0; c <= maxNotes; c++) {
                    int rest = best[i - 1][a - c * size];
                    if (rest + c < best[i][a]) {
                        best[i][a] = rest + c;
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
        for (int i = n; i >= 1; i--) {                    // walk the table backwards
            counts[i - 1] = used[i][a];
            a -= counts[i - 1] * (denominations.get(i - 1) / unit);
        }
        Map<Integer, Integer> plan = new LinkedHashMap<>();  // largest note first, like the greedy plan
        for (int i = 0; i < n; i++) {
            if (counts[i] > 0) {
                plan.put(denominations.get(i), counts[i]);
            }
        }
        return Optional.of(plan);
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
