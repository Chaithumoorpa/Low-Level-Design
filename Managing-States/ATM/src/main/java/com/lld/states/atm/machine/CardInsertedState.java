package com.lld.states.atm.machine;

import com.lld.states.atm.bank.AuthResult;
import com.lld.states.atm.model.AtmException;

/** A card is in the slot; the customer must enter the PIN (or take the card back). */
final class CardInsertedState implements AtmState {

    static final CardInsertedState INSTANCE = new CardInsertedState();

    private CardInsertedState() {
    }

    @Override
    public AtmStatus status() {
        return AtmStatus.CARD_INSERTED;
    }

    @Override
    public void enterPin(Atm atm, String pin) {
        AuthResult result = atm.bank().authenticate(atm.currentCard(), pin);
        switch (result.status()) {
            case SUCCESS -> atm.transitionTo(AuthenticatedState.INSTANCE);
            case WRONG_PIN -> throw AtmException.declined(
                    "Wrong PIN. " + result.attemptsLeft() + " attempt(s) left.");
            case CARD_BLOCKED -> {
                atm.retainCard(atm.currentCard());
                atm.endSession();
                throw AtmException.declined("Too many wrong PINs. Your card has been blocked and retained.");
            }
            case UNKNOWN_CARD -> {
                atm.endSession();
                throw AtmException.declined("Card not recognised. Please take your card.");
            }
        }
    }

    @Override
    public void ejectCard(Atm atm) {
        atm.endSession();
    }
}
