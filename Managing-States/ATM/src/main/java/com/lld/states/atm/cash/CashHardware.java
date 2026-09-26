package com.lld.states.atm.cash;

import java.util.Map;

/**
 * The physical note dispenser. Abstracted so tests can simulate a jam: real hardware can fail
 * AFTER the account was debited, and the ATM must then refund.
 */
@FunctionalInterface
public interface CashHardware {

    /** Pushes the notes out of the slot, or throws if the mechanism fails. */
    void eject(Map<Integer, Integer> notes);

    CashHardware ALWAYS_WORKS = notes -> { };
}
