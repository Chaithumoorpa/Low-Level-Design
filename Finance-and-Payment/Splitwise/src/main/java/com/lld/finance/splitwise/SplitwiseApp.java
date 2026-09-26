package com.lld.finance.splitwise;

import com.lld.finance.splitwise.model.Group;
import com.lld.finance.splitwise.model.Money;
import com.lld.finance.splitwise.model.SplitwiseException;
import com.lld.finance.splitwise.model.Transfer;
import com.lld.finance.splitwise.service.SplitwiseService;
import com.lld.finance.splitwise.split.Split;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Four friends on a weekend trip. */
public class SplitwiseApp {

    public static void main(String[] args) {
        SplitwiseService app = new SplitwiseService(Clock.fixed(Instant.parse("2027-03-06T10:00:00Z"), ZoneOffset.UTC));
        app.addUser("ana", "Ana");
        app.addUser("raj", "Raj");
        app.addUser("kai", "Kai");
        app.addUser("mo", "Mo");
        Group trip = app.createGroup("Goa trip", "ana", "raj", "kai", "mo");

        step("Expenses");
        app.addExpense(trip.id(), "ana", "Villa", "ana", 400_00, Split.equal("ana", "raj", "kai", "mo"));
        app.addExpense(trip.id(), "raj", "Dinner", "raj", 100_00, Split.equal("ana", "raj", "kai"));
        app.addExpense(trip.id(), "kai", "Scooters", "kai", 90_00,
                Split.exact(Map.of("kai", 30_00L, "mo", 60_00L)));
        app.addExpense(trip.id(), "mo", "Boat tour", "mo", 150_00,
                Split.percent(Map.of("ana", 2500, "raj", 2500, "kai", 2500, "mo", 2500)));
        app.addExpense(trip.id(), "ana", "Groceries", "ana", 70_00,
                Split.shares(Map.of("ana", 1, "raj", 2, "kai", 2, "mo", 2)));
        app.activity(trip.id()).forEach(a -> System.out.println("   " + a));
        System.out.println("   Dinner split 3 ways: " + app.expenses(trip.id()).get(1).owed() + " (cents, adds up to 10000)");

        step("Bad input is refused");
        attempt(() -> app.addExpense(trip.id(), "ana", "Taxi", "ana", 30_00, Split.exact(Map.of("ana", 10_00L, "raj", 10_00L))));
        attempt(() -> app.addExpense(trip.id(), "ana", "Taxi", "ana", 30_00, Split.percent(Map.of("ana", 5000, "raj", 4000))));

        step("Balances");
        printBalances(app, trip.id());
        System.out.println("   pairwise debts (" + app.debts(trip.id()).size() + " payments): " + app.debts(trip.id()));
        List<Transfer> plan = app.simplifiedDebts(trip.id());
        System.out.println("   simplified (" + plan.size() + " payments): " + plan);

        step("Raj notices the dinner was $120, not $100");
        app.editExpense(app.expenses(trip.id()).get(1).id(), "raj", "Dinner", "raj", 120_00, Split.equal("ana", "raj", "kai"));
        printBalances(app, trip.id());

        step("Everyone settles with the simplified plan");
        for (Transfer t : app.simplifiedDebts(trip.id())) {
            app.settleUp(trip.id(), t.fromId(), t.toId(), t.amountCents());
            System.out.println("   " + t.fromId() + " paid " + t.toId() + " " + Money.format(t.amountCents()));
        }
        printBalances(app, trip.id());
        app.removeMember(trip.id(), "mo");
        System.out.println("   Mo is settled and leaves the group");
    }

    private static void printBalances(SplitwiseService app, String groupId) {
        app.netBalances(groupId).forEach((u, v) -> System.out.println("   " + u + " "
                + (v > 0 ? "gets back " + Money.format(v) : v < 0 ? "owes " + Money.format(-v) : "is settled up")));
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Runnable action) {
        try {
            action.run();
            System.out.println("   ok");
        } catch (SplitwiseException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
