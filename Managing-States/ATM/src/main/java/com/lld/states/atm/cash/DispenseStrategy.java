package com.lld.states.atm.cash;

import java.util.Map;
import java.util.Optional;

/**
 * Strategy: which notes to hand out for an amount, given what is in the cassettes.
 * Returns empty if the amount cannot be made from the available notes.
 */
public interface DispenseStrategy {

    Optional<Map<Integer, Integer>> plan(long amount, CashInventory inventory);
}
