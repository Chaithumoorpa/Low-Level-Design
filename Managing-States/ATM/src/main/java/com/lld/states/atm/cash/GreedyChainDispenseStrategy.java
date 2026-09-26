package com.lld.states.atm.cash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The textbook ATM answer: a Chain of Responsibility of note handlers, largest note first. Each
 * handler takes as many of its notes as it can, then passes the rest down the chain.
 *
 * <p>Simple, and correct for unlimited notes of "canonical" denominations. With a LIMITED
 * inventory it can fail when an answer exists: 600 with one 500 and three 200s. Greedy takes the
 * 500, leaving 100 that nobody can pay, although 3 x 200 works. See
 * {@link OptimalDispenseStrategy} and the tests.
 */
public class GreedyChainDispenseStrategy implements DispenseStrategy {

    /** One link of the chain: handles a single denomination. */
    private static final class NoteHandler {
        private final int denomination;
        private final NoteHandler next;

        NoteHandler(int denomination, NoteHandler next) {
            this.denomination = denomination;
            this.next = next;
        }

        /** @return the amount this handler and the rest of the chain could NOT pay */
        long handle(long amount, CashInventory inventory, Map<Integer, Integer> plan) {
            long notes = Math.min(amount / denomination, inventory.count(denomination));
            if (notes > 0) {
                plan.put(denomination, (int) notes);
                amount -= notes * denomination;
            }
            return next == null || amount == 0 ? amount : next.handle(amount, inventory, plan);
        }
    }

    @Override
    public Optional<Map<Integer, Integer>> plan(long amount, CashInventory inventory) {
        NoteHandler chain = null;
        for (int d : inventory.snapshot().descendingKeySet()) {    // build from smallest up, so largest is first
            chain = new NoteHandler(d, chain);
        }
        Map<Integer, Integer> plan = new LinkedHashMap<>();
        long unpaid = chain.handle(amount, inventory, plan);
        return unpaid == 0 ? Optional.of(plan) : Optional.empty();
    }
}
