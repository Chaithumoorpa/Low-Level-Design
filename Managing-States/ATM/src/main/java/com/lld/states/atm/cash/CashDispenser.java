package com.lld.states.atm.cash;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Facade over the cassettes (inventory), the note-selection strategy and the hardware.
 * Notes leave the inventory only after the hardware confirms they were pushed out.
 */
public class CashDispenser {

    private final CashInventory inventory;
    private final DispenseStrategy strategy;
    private final CashHardware hardware;

    public CashDispenser(CashInventory inventory, DispenseStrategy strategy, CashHardware hardware) {
        this.inventory = Objects.requireNonNull(inventory);
        this.strategy = Objects.requireNonNull(strategy);
        this.hardware = Objects.requireNonNull(hardware);
    }

    /** Which notes would be used, without dispensing anything. */
    public Optional<Map<Integer, Integer>> plan(long amount) {
        return strategy.plan(amount, inventory);
    }

    /** @throws RuntimeException from the hardware; the inventory is unchanged in that case */
    public void dispense(Map<Integer, Integer> plan) {
        hardware.eject(plan);
        inventory.remove(plan);
    }

    public void accept(Map<Integer, Integer> notes) {
        inventory.add(notes);
    }

    public boolean accepts(int denomination) {
        return inventory.accepts(denomination);
    }

    public long totalCash() {
        return inventory.total();
    }

    public int smallestDenomination() {
        return inventory.smallestDenomination();
    }

    /** Greatest common divisor of the denominations: every payable amount is a multiple of it. */
    public int unit() {
        int g = 0;
        for (int d : inventory.snapshot().keySet()) {
            g = gcd(g, d);
        }
        return g;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** True when not even the smallest note is left in stock. */
    public boolean isEmpty() {
        for (int count : inventory.snapshot().values()) {
            if (count > 0) {
                return false;
            }
        }
        return true;
    }

    public CashInventory inventory() {
        return inventory;
    }
}
