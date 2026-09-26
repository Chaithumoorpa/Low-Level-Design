package com.lld.states.atm.machine;

import com.lld.states.atm.model.AtmException;
import com.lld.states.atm.model.Card;

import java.util.Map;

/**
 * State pattern: every button the ATM has, with "not allowed here" as the default.
 * Each state class overrides only the actions that make sense on its screen, so the rules of the
 * machine can be read one state at a time instead of as a big switch in every method.
 *
 * <p>States keep no data (the card, the bank and the cash live in {@link Atm}), so each state is a
 * single shared instance.
 */
interface AtmState {

    AtmStatus status();

    default void insertCard(Atm atm, Card card) {
        throw notHere("insert a card");
    }

    default void enterPin(Atm atm, String pin) {
        throw notHere("enter a PIN");
    }

    default Map<Integer, Integer> withdraw(Atm atm, long amount) {
        throw notHere("withdraw");
    }

    default void deposit(Atm atm, Map<Integer, Integer> notes) {
        throw notHere("deposit");
    }

    default long checkBalance(Atm atm) {
        throw notHere("check the balance");
    }

    default void ejectCard(Atm atm) {
        throw notHere("eject a card");
    }

    default void startMaintenance(Atm atm) {
        throw notHere("start maintenance");
    }

    default void refill(Atm atm, Map<Integer, Integer> notes) {
        throw notHere("refill cash");
    }

    default void finishMaintenance(Atm atm) {
        throw notHere("finish maintenance");
    }

    private AtmException notHere(String action) {
        return AtmException.invalidState(action, status().name());
    }
}
