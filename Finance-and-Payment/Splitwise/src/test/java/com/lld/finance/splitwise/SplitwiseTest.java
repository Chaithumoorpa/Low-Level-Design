package com.lld.finance.splitwise;

import com.lld.finance.splitwise.model.Expense;
import com.lld.finance.splitwise.model.Group;
import com.lld.finance.splitwise.model.SplitwiseException;
import com.lld.finance.splitwise.model.Transfer;
import com.lld.finance.splitwise.service.DebtSimplifier;
import com.lld.finance.splitwise.service.SplitwiseService;
import com.lld.finance.splitwise.split.Split;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitwiseTest {

    private SplitwiseService app;
    private Group g;

    @BeforeEach
    void setUp() {
        app = new SplitwiseService(Clock.fixed(Instant.parse("2027-03-06T10:00:00Z"), ZoneOffset.UTC));
        for (String u : List.of("a", "b", "c", "d", "x")) {
            app.addUser(u, u.toUpperCase());
        }
        g = app.createGroup("trip", "a", "b", "c", "d");
    }

    private static long sum(Map<String, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }

    // ------------------------------------------------------------------ splits

    @Nested
    class Splits {

        @ParameterizedTest(name = "{0}c among {1}")
        @CsvSource({"10000,3", "1,3", "2,3", "100,7", "99999,4", "5,5"})
        void equalSplitAddsUpAndDiffersByAtMostOneCent(long total, int people) {
            String[] who = new String[people];
            for (int i = 0; i < people; i++) {
                who[i] = "u" + i;
            }
            Map<String, Long> owed = Split.equal(who).owed(total);
            assertEquals(total, sum(owed));
            long min = owed.values().stream().mapToLong(Long::longValue).min().orElseThrow();
            long max = owed.values().stream().mapToLong(Long::longValue).max().orElseThrow();
            assertTrue(max - min <= 1);
        }

        @Test
        void leftoverCentsGoToTheFirstListed() {
            assertEquals(Map.of("a", 3334L, "b", 3333L, "c", 3333L), Split.equal("a", "b", "c").owed(10000));
            assertEquals(List.of(1L, 1L, 0L), List.copyOf(Split.equal("a", "b", "c").owed(2).values()));
        }

        @Test
        void percentUsesLargestRemainder() {
            Map<String, Integer> p = new LinkedHashMap<>();
            p.put("a", 3333);
            p.put("b", 3333);
            p.put("c", 3334);
            Map<String, Long> owed = Split.percent(p).owed(100);
            assertEquals(100, sum(owed));
            assertEquals(34L, owed.get("c"), "c has the biggest fractional part");
            assertThrows(SplitwiseException.class, () -> Split.percent(Map.of("a", 5000, "b", 4000)).owed(100));
            assertThrows(SplitwiseException.class, () -> Split.percent(Map.of("a", 11000, "b", -1000)).owed(100));
        }

        @Test
        void sharesAreProportional() {
            Map<String, Integer> s = new LinkedHashMap<>();
            s.put("a", 1);
            s.put("b", 2);
            s.put("c", 2);
            assertEquals(Map.of("a", 1400L, "b", 2800L, "c", 2800L), Split.shares(s).owed(7000));
            assertThrows(SplitwiseException.class, () -> Split.shares(Map.of("a", 0)).owed(100));
        }

        @Test
        void exactMustMatchTheTotal() {
            assertEquals(Map.of("a", 30L, "b", 70L), Split.exact(Map.of("a", 30L, "b", 70L)).owed(100));
            assertThrows(SplitwiseException.class, () -> Split.exact(Map.of("a", 30L)).owed(100));
            assertThrows(SplitwiseException.class, () -> Split.exact(Map.of("a", -30L, "b", 130L)).owed(100));
        }

        @Test
        void equalNeedsDistinctParticipants() {
            assertThrows(SplitwiseException.class, () -> Split.equal("a", "a"));
            assertThrows(SplitwiseException.class, () -> Split.equal());
        }
    }

    // ------------------------------------------------------------------ balances

    @Nested
    class Balances {

        @Test
        void netBalancesFromOneExpense() {
            app.addExpense(g.id(), "a", "dinner", "a", 9000, Split.equal("a", "b", "c"));
            assertEquals(Map.of("a", 6000L, "b", -3000L, "c", -3000L, "d", 0L), app.netBalances(g.id()));
            assertEquals(List.of(new Transfer("b", "a", 3000), new Transfer("c", "a", 3000)), app.debts(g.id()));
        }

        @Test
        void opposingDebtsNetOutPerPair() {
            app.addExpense(g.id(), "a", "x", "a", 10000, Split.equal("a", "b"));    // b owes a 50
            app.addExpense(g.id(), "b", "y", "b", 6000, Split.equal("a", "b"));     // a owes b 30
            assertEquals(List.of(new Transfer("b", "a", 2000)), app.debts(g.id()));
        }

        @Test
        void simplificationUsesFewerPayments() {
            app.addExpense(g.id(), "a", "1", "a", 3000, Split.exact(Map.of("b", 3000L)));   // b owes a 30
            app.addExpense(g.id(), "b", "2", "b", 3000, Split.exact(Map.of("c", 3000L)));   // c owes b 30
            assertEquals(2, app.debts(g.id()).size());
            assertEquals(List.of(new Transfer("c", "a", 3000)), app.simplifiedDebts(g.id()), "b is just a middleman");
        }

        @Test
        void circularDebtsCancelOut() {
            app.addExpense(g.id(), "a", "1", "a", 3000, Split.exact(Map.of("b", 3000L)));   // b owes a 30
            app.addExpense(g.id(), "b", "2", "b", 5000, Split.exact(Map.of("c", 5000L)));   // c owes b 50
            app.addExpense(g.id(), "c", "3", "c", 3000, Split.exact(Map.of("a", 3000L)));   // a owes c 30
            assertEquals(List.of(new Transfer("c", "b", 2000)), app.debts(g.id()), "the 30-30-30 loop is gone");
            assertEquals(app.simplifiedDebts(g.id()), app.debts(g.id()));
        }

        @Test
        void editAndDeleteReflowBalances() {
            Expense e = app.addExpense(g.id(), "a", "taxi", "a", 4000, Split.equal("a", "b"));
            app.editExpense(e.id(), "b", "taxi", "b", 6000, Split.equal("a", "b"));
            assertEquals(Map.of("a", -3000L, "b", 3000L, "c", 0L, "d", 0L), app.netBalances(g.id()));
            app.deleteExpense(e.id(), "c");
            assertEquals(0, app.netBalances(g.id()).values().stream().filter(v -> v != 0).count());
            assertTrue(app.activity(g.id()).get(app.activity(g.id()).size() - 1).contains("deleted"));
        }

        @Test
        void settlingThroughTheSimplifiedPlanZeroesEveryone() {
            app.addExpense(g.id(), "a", "villa", "a", 40000, Split.equal("a", "b", "c", "d"));
            app.addExpense(g.id(), "b", "food", "b", 10000, Split.equal("a", "b", "c"));
            app.addExpense(g.id(), "c", "boat", "c", 7500, Split.shares(Map.of("b", 1, "d", 2)));
            for (Transfer t : app.simplifiedDebts(g.id())) {
                app.settleUp(g.id(), t.fromId(), t.toId(), t.amountCents());
            }
            assertTrue(app.netBalances(g.id()).values().stream().allMatch(v -> v == 0));
            assertEquals(List.of(), app.debts(g.id()));
        }

        @Test
        void overpayingFlipsTheDirection() {
            app.addExpense(g.id(), "a", "x", "a", 2000, Split.equal("a", "b"));
            app.settleUp(g.id(), "b", "a", 1500);
            assertEquals(List.of(new Transfer("a", "b", 500)), app.debts(g.id()));
        }

        @Test
        void balancesAcrossGroups() {
            Group flat = app.createGroup("flat", "a", "b");
            app.addExpense(g.id(), "a", "x", "a", 2000, Split.equal("a", "b"));      // b owes a 10
            app.addExpense(flat.id(), "b", "y", "b", 6000, Split.equal("a", "b"));   // a owes b 30
            assertEquals(-2000, app.balanceBetween("a", "b"));
            assertEquals(2000, app.balanceBetween("b", "a"));
            assertEquals(-2000, app.overallBalance("a"));
        }
    }

    // ------------------------------------------------------------------ rules

    @Test
    void membershipRules() {
        assertThrows(SplitwiseException.class, () -> app.addExpense(g.id(), "a", "x", "x", 100, Split.equal("a", "b")),
                "payer not in group");
        assertThrows(SplitwiseException.class, () -> app.addExpense(g.id(), "a", "x", "a", 100, Split.equal("a", "x")),
                "participant not in group");
        assertThrows(SplitwiseException.class, () -> app.addExpense(g.id(), "a", "x", "a", 0, Split.equal("a")));
        assertThrows(SplitwiseException.class, () -> app.settleUp(g.id(), "a", "a", 100));
        assertThrows(SplitwiseException.class, () -> app.settleUp(g.id(), "a", "b", 0));
        app.addExpense(g.id(), "a", "x", "a", 2000, Split.equal("a", "b"));
        assertThrows(SplitwiseException.class, () -> app.removeMember(g.id(), "b"), "b still owes");
        app.settleUp(g.id(), "b", "a", 1000);
        app.removeMember(g.id(), "b");
        assertTrue(!app.group(g.id()).has("b"));
        app.removeMember(g.id(), "d");
    }

    // ------------------------------------------------------------------ properties

    @Test
    void randomExpensesAlwaysBalanceAndSimplifyInAtMostNMinusOnePayments() {
        Random random = new Random(3);
        List<String> people = List.of("a", "b", "c", "d");
        for (int i = 0; i < 300; i++) {
            String payer = people.get(random.nextInt(4));
            List<String> who = new ArrayList<>(people);
            java.util.Collections.shuffle(who, random);
            who = who.subList(0, 1 + random.nextInt(4));
            long total = 1 + random.nextInt(50_000);
            Split split = switch (random.nextInt(3)) {
                case 0 -> Split.equal(who.toArray(String[]::new));
                case 1 -> {
                    Map<String, Integer> sh = new LinkedHashMap<>();
                    who.forEach(u -> sh.put(u, 1 + random.nextInt(5)));
                    yield Split.shares(sh);
                }
                default -> {
                    Map<String, Long> ex = new LinkedHashMap<>();
                    long left = total;
                    for (int k = 0; k < who.size() - 1; k++) {
                        long part = random.nextLong(left + 1);
                        ex.put(who.get(k), part);
                        left -= part;
                    }
                    ex.put(who.get(who.size() - 1), left);
                    yield Split.exact(ex);
                }
            };
            app.addExpense(g.id(), payer, "e" + i, payer, total, split);
            if (random.nextInt(10) == 0) {
                app.settleUp(g.id(), people.get(random.nextInt(2)), people.get(2 + random.nextInt(2)), 1 + random.nextInt(5000));
            }
        }
        Map<String, Long> net = app.netBalances(g.id());
        assertEquals(0, sum(net));
        List<Transfer> plan = app.simplifiedDebts(g.id());
        assertTrue(plan.size() <= 3);
        Map<String, Long> after = new HashMap<>(net);
        plan.forEach(t -> {
            after.merge(t.fromId(), t.amountCents(), Long::sum);
            after.merge(t.toId(), -t.amountCents(), Long::sum);
        });
        assertTrue(after.values().stream().allMatch(v -> v == 0), "the plan settles everyone");
        long pairwiseTotal = app.debts(g.id()).stream().mapToLong(Transfer::amountCents).sum();
        long simplifiedTotal = plan.stream().mapToLong(Transfer::amountCents).sum();
        assertTrue(simplifiedTotal <= pairwiseTotal, "simplifying never moves more money");
    }

    @Test
    void simplifierRejectsUnbalancedInput() {
        assertThrows(IllegalArgumentException.class, () -> DebtSimplifier.simplify(Map.of("a", 10L, "b", -5L)));
        assertEquals(List.of(), DebtSimplifier.simplify(Map.of("a", 0L)));
    }
}
