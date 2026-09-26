package com.lld.states.atm.machine;

import com.lld.states.atm.model.AtmException;
import com.lld.states.atm.model.Card;

/** Waiting for a customer. The only customer action is inserting a card. */
final class IdleState implements AtmState {

    static final IdleState INSTANCE = new IdleState();

    private IdleState() {
    }

    @Override
    public AtmStatus status() {
        return AtmStatus.IDLE;
    }

    @Override
    public void insertCard(Atm atm, Card card) {
        if (atm.bank().isCardBlocked(card)) {
            atm.retainCard(card);
            throw AtmException.declined("This card is blocked. It has been retained; please contact your bank.");
        }
        atm.acceptCard(card);
        atm.transitionTo(CardInsertedState.INSTANCE);
    }

    /** Staff can only take the machine offline between customers, never mid-session. */
    @Override
    public void startMaintenance(Atm atm) {
        atm.transitionTo(OutOfServiceState.INSTANCE);
    }
}
