package com.lld.states.atm;

import com.lld.states.atm.bank.InMemoryBank;
import com.lld.states.atm.cash.CashDispenser;
import com.lld.states.atm.cash.CashHardware;
import com.lld.states.atm.cash.CashInventory;
import com.lld.states.atm.cash.GreedyChainDispenseStrategy;
import com.lld.states.atm.cash.OptimalDispenseStrategy;
import com.lld.states.atm.machine.Atm;
import com.lld.states.atm.machine.AtmEventListener;
import com.lld.states.atm.machine.AtmStatus;
import com.lld.states.atm.model.AtmException;
import com.lld.states.atm.model.Card;

import java.time.Clock;
import java.util.Map;

/** Scripted walk-through of the ATM's states and edge cases. */
public class AtmApp {

    public static void main(String[] args) {
        InMemoryBank bank = new InMemoryBank();
        Card alice = new Card("4111111111111111");
        Card bob = new Card("5500000000000004");
        bank.openAccount(alice, "1234", 50_000, 25_000);
        bank.openAccount(bob, "4321", 3_000, 10_000);

        CashInventory cash = new CashInventory(2000, 500, 200, 100);
        cash.add(Map.of(2000, 5, 500, 10, 200, 10, 100, 10));
        Atm atm = new Atm("ATM-042", bank, new CashDispenser(cash, new OptimalDispenseStrategy(), CashHardware.ALWAYS_WORKS),
                20_000, Clock.systemDefaultZone());
        atm.addListener(new AtmEventListener() {
            @Override
            public void onStateChange(AtmStatus from, AtmStatus to) {
                System.out.println("      [screen] " + from + " -> " + to);
            }

            @Override
            public void onCardRetained(Card card) {
                System.out.println("      [alert] card " + card + " retained");
            }
        });

        System.out.println("ATM-042 loaded with " + atm.cashAvailable());

        step("Alice inserts her card", () -> atm.insertCard(alice));
        step("Alice tries to withdraw before entering a PIN", () -> atm.withdraw(1000));
        step("Alice enters a wrong PIN", () -> atm.enterPin("0000"));
        step("Alice enters the right PIN", () -> atm.enterPin("1234"));
        step("Alice withdraws 3,700", () -> System.out.println("      notes: " + atm.withdraw(3700)));
        step("Alice withdraws 150 (not a multiple of 100)", () -> atm.withdraw(150));
        step("Alice deposits 2 x 500", () -> atm.deposit(Map.of(500, 2)));
        step("Alice checks her balance", () -> System.out.println("      balance: " + atm.checkBalance()));
        step("Alice takes her card", atm::ejectCard);

        step("Bob inserts his card", () -> atm.insertCard(bob));
        for (String pin : new String[]{"1111", "2222", "3333"}) {
            step("Bob enters PIN " + pin, () -> atm.enterPin(pin));
        }
        step("Bob's card comes back later", () -> atm.insertCard(bob));

        step("Staff take the ATM offline", atm::startMaintenance);
        step("A customer tries to use it", () -> atm.insertCard(alice));
        step("Staff refill 10 x 2000 and bring it back", () -> {
            atm.refill(Map.of(2000, 10));
            atm.finishMaintenance();
        });

        System.out.println();
        System.out.println("Journal:");
        atm.journal().forEach(r -> System.out.println("  " + r));

        System.out.println();
        System.out.println("Why the dispenser is not greedy: 600 from {500 x 1, 200 x 3}");
        CashInventory tricky = new CashInventory(500, 200);
        tricky.add(Map.of(500, 1, 200, 3));
        System.out.println("  greedy (chain of responsibility): " + new GreedyChainDispenseStrategy().plan(600, tricky)
                .map(Object::toString).orElse("cannot pay"));
        System.out.println("  optimal (dynamic programming):    " + new OptimalDispenseStrategy().plan(600, tricky)
                .map(Object::toString).orElse("cannot pay"));
    }

    private static void step(String title, Runnable action) {
        System.out.println();
        System.out.println("> " + title);
        try {
            action.run();
        } catch (AtmException e) {
            System.out.println("      " + (e.isInvalidState() ? "[not allowed] " : "[declined] ") + e.getMessage());
        }
    }
}
