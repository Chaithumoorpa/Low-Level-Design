package com.lld.finance.splitwise.service;

import com.lld.finance.splitwise.model.Transfer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Turns net balances into a short list of payments. Only each person's <em>net</em> position matters:
 * if Ana is owed $50 overall and Kai owes $50 overall, Kai can pay Ana directly even if they never
 * shared an expense.
 *
 * <p>Greedy: repeatedly let the biggest debtor pay the biggest creditor as much as possible. Each step
 * settles at least one person, so there are at most n-1 payments. (The true minimum is NP-hard in
 * general; greedy is what apps use in practice.)
 */
public final class DebtSimplifier {

    private DebtSimplifier() {
    }

    private record Party(String id, long amount) {
    }

    /** @param net user -> cents (positive = is owed money, negative = owes); must sum to zero */
    public static List<Transfer> simplify(Map<String, Long> net) {
        long sum = net.values().stream().mapToLong(Long::longValue).sum();
        if (sum != 0) {
            throw new IllegalArgumentException("Balances must sum to zero, got " + sum);
        }
        Comparator<Party> biggestFirst = Comparator.comparingLong(Party::amount).reversed().thenComparing(Party::id);
        PriorityQueue<Party> creditors = new PriorityQueue<>(biggestFirst);
        PriorityQueue<Party> debtors = new PriorityQueue<>(biggestFirst);
        net.forEach((u, v) -> {
            if (v > 0) {
                creditors.add(new Party(u, v));
            } else if (v < 0) {
                debtors.add(new Party(u, -v));
            }
        });
        List<Transfer> out = new ArrayList<>();
        while (!creditors.isEmpty()) {
            Party c = creditors.poll();
            Party d = debtors.poll();
            long pay = Math.min(c.amount(), d.amount());
            out.add(new Transfer(d.id(), c.id(), pay));
            if (c.amount() > pay) {
                creditors.add(new Party(c.id(), c.amount() - pay));
            }
            if (d.amount() > pay) {
                debtors.add(new Party(d.id(), d.amount() - pay));
            }
        }
        return out;
    }
}
