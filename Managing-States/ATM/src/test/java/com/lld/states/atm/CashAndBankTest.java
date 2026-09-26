package com.lld.states.atm;

import com.lld.states.atm.bank.InMemoryBank;
import com.lld.states.atm.cash.CashInventory;
import com.lld.states.atm.cash.GreedyChainDispenseStrategy;
import com.lld.states.atm.cash.OptimalDispenseStrategy;
import com.lld.states.atm.model.AtmException;
import com.lld.states.atm.model.Card;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CashAndBankTest {

    // ------------------------------------------------------------------ dispensing strategies

    @Test
    void greedyChainWorksWithPlentyOfNotes() {
        CashInventory cash = new CashInventory(2000, 500, 200, 100);
        cash.add(Map.of(2000, 10, 500, 10, 200, 10, 100, 10));

        assertEquals(Optional.of(Map.of(2000, 2, 500, 1, 200, 1, 100, 1)),
                new GreedyChainDispenseStrategy().plan(4800, cash));
    }

    @Test
    void greedyFailsWhereAnAnswerExistsButOptimalFindsIt() {
        CashInventory cash = new CashInventory(500, 200);
        cash.add(Map.of(500, 1, 200, 3));

        assertEquals(Optional.empty(), new GreedyChainDispenseStrategy().plan(600, cash));
        assertEquals(Optional.of(Map.of(200, 3)), new OptimalDispenseStrategy().plan(600, cash));
    }

    @Test
    void optimalUsesTheFewestNotes() {
        CashInventory cash = new CashInventory(500, 200, 100);
        cash.add(Map.of(500, 2, 200, 5, 100, 5));

        // 1000 = 2 x 500 (2 notes), not 5 x 200 or 10 x 100
        assertEquals(Optional.of(Map.of(500, 2)), new OptimalDispenseStrategy().plan(1000, cash));
    }

    /** Brute force over every combination proves the DP finds a plan exactly when one exists, with the fewest notes. */
    @Test
    void optimalMatchesBruteForceOnRandomInventories() {
        Random random = new Random(9);
        int[] denominations = {2000, 500, 200, 100};
        OptimalDispenseStrategy optimal = new OptimalDispenseStrategy();
        for (int trial = 0; trial < 300; trial++) {
            CashInventory cash = new CashInventory(denominations);
            int[] counts = new int[4];
            for (int i = 0; i < 4; i++) {
                counts[i] = random.nextInt(4);
                cash.add(Map.of(denominations[i], counts[i]));
            }
            long amount = 100L * (1 + random.nextInt(80));

            int bestNotes = Integer.MAX_VALUE;
            for (int a = 0; a <= counts[0]; a++) {
                for (int b = 0; b <= counts[1]; b++) {
                    for (int c = 0; c <= counts[2]; c++) {
                        for (int d = 0; d <= counts[3]; d++) {
                            if (2000L * a + 500L * b + 200L * c + 100L * d == amount) {
                                bestNotes = Math.min(bestNotes, a + b + c + d);
                            }
                        }
                    }
                }
            }

            Optional<Map<Integer, Integer>> plan = optimal.plan(amount, cash);
            if (bestNotes == Integer.MAX_VALUE) {
                assertTrue(plan.isEmpty(), "trial " + trial + ": plan for impossible " + amount);
            } else {
                Map<Integer, Integer> p = plan.orElseThrow();
                long paid = p.entrySet().stream().mapToLong(e -> (long) e.getKey() * e.getValue()).sum();
                int notes = p.values().stream().mapToInt(Integer::intValue).sum();
                assertEquals(amount, paid, "trial " + trial);
                assertEquals(bestNotes, notes, "trial " + trial + ": not the fewest notes");
                p.forEach((den, n) -> assertTrue(n <= cash.count(den), "uses notes it does not have"));
            }
        }
    }

    @Test
    void inventoryRejectsUnknownDenominationsAndOverdraw() {
        CashInventory cash = new CashInventory(500, 100);
        cash.add(Map.of(500, 1));

        assertThrows(IllegalArgumentException.class, () -> cash.add(Map.of(200, 1)));
        assertThrows(IllegalStateException.class, () -> cash.remove(Map.of(500, 2)));
        assertEquals(500, cash.total());
        assertEquals(100, cash.smallestDenomination());
    }

    // ------------------------------------------------------------------ bank

    @Test
    void twoAtmsCannotOverdrawOneAccountConcurrently() throws Exception {
        InMemoryBank bank = new InMemoryBank();
        Card card = new Card("4000000000000002");
        bank.openAccount(card, "9999", 10_000, 1_000_000);

        AtomicInteger approved = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    try {
                        bank.debit(card, 100);
                        approved.incrementAndGet();
                    } catch (AtmException declined) {
                        // insufficient funds: expected once the money runs out
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(100, approved.get(), "exactly 10,000 / 100 debits may succeed");
        assertEquals(0, bank.balance(card));
    }

    @Test
    void blockedCardCanBeUnblockedByTheBranch() {
        InMemoryBank bank = new InMemoryBank();
        Card card = new Card("4000000000000002");
        bank.openAccount(card, "9999", 100, 100);
        for (int i = 0; i < InMemoryBank.MAX_PIN_ATTEMPTS; i++) {
            bank.authenticate(card, "0000");
        }
        assertTrue(bank.isCardBlocked(card));
        assertFalse(bank.authenticate(card, "9999").isSuccess());   // even the right PIN is refused now

        bank.unblock(card);

        assertTrue(bank.authenticate(card, "9999").isSuccess());
    }
}
