package com.lld.states.vending;

import com.lld.states.vending.machine.VendingListener;
import com.lld.states.vending.machine.VendingMachine;
import com.lld.states.vending.machine.VendingStatus;
import com.lld.states.vending.model.Money;
import com.lld.states.vending.model.VendingException;

import java.util.Map;

/** Scripted walk-through: buying, change, cancelling, exact-change-only, sold out, maintenance. */
public class VendingMachineApp {

    public static void main(String[] args) {
        VendingMachine vm = VendingMachine.builder()
                .acceptCoins(5, 10, 25, 100)
                .slot("A1", "Cola", 125, 5, 3)
                .slot("A2", "Water", 90, 5, 1)
                .slot("B1", "Chips", 150, 5, 2)
                .changeFloat(Map.of(25, 1, 10, 3))            // a small float: 55c
                .build();
        vm.addListener(new VendingListener() {
            @Override
            public void onStateChange(VendingStatus from, VendingStatus to) {
                System.out.println("      [display] " + from + " -> " + to);
            }

            @Override
            public void onSoldOut(String slotCode) {
                System.out.println("      [telemetry] slot " + slotCode + " is now empty");
            }
        });

        System.out.println("Machine: " + vm.slots());

        step("Select Cola with no money", () -> vm.selectProduct("A1"));
        step("Insert $1.00", () -> vm.insertCoin(100));
        step("Select Cola ($1.25)", () -> vm.selectProduct("A1"));
        step("Insert 25c", () -> vm.insertCoin(25));
        step("Select Cola", () -> System.out.println("      tray: " + vm.selectProduct("A1")));

        step("Insert $1.00 and select Water ($0.90): change 10c", () -> {
            vm.insertCoin(100);
            System.out.println("      tray: " + vm.selectProduct("A2"));
        });
        step("Select Water again", () -> {
            vm.insertCoin(100);
            vm.selectProduct("A2");
        });
        step("Cancel: the same coins come back", () ->
                System.out.println("      returned: " + Money.describe(vm.cancel())));

        step("Insert a 1c coin", () -> vm.insertCoin(1));
        System.out.println("      coin return: " + Money.describe(vm.takeCoinReturn()));

        step("Insert $1.00 + $1.00 for Chips ($1.50): 50c change needed", () -> {
            vm.insertCoin(100);
            vm.insertCoin(100);
            System.out.println("      tray: " + vm.selectProduct("B1"));
        });
        step("Again $2.00 for Chips: can the float still pay 50c?", () -> {
            vm.insertCoin(100);
            vm.insertCoin(100);
            vm.selectProduct("B1");
        });
        step("Customer pays exact change instead", () -> {
            vm.cancel();
            vm.insertCoin(100);
            vm.insertCoin(25);
            vm.insertCoin(25);
            System.out.println("      tray: " + vm.selectProduct("B1"));
        });

        step("Operator: restock water, raise the cola price, collect cash", () -> {
            vm.startMaintenance();
            vm.restock("A2", 4);
            vm.setPrice("A1", 150);
            System.out.println("      collected: " + Money.format(Money.total(vm.collectCash())));
            vm.loadCoins(Map.of(25, 10, 10, 10, 5, 10));
            vm.finishMaintenance();
        });

        System.out.println();
        System.out.println("Sales: " + vm.unitsSold() + ", revenue " + Money.format(vm.revenue()));
        System.out.println("Stock: " + vm.slots());
    }

    private static void step(String title, Runnable action) {
        System.out.println();
        System.out.println("> " + title);
        try {
            action.run();
        } catch (VendingException e) {
            System.out.println("      " + (e.isInvalidState() ? "[not allowed] " : "[declined] ") + e.getMessage());
        }
    }
}
