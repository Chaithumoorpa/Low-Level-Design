package com.lld.states.atm.machine;

import com.lld.states.atm.model.AtmException;

import java.util.Map;

/**
 * Offline: no cash left, a hardware fault, or staff maintenance. Customers can do nothing;
 * staff can refill cash and bring the machine back.
 */
final class OutOfServiceState implements AtmState {

    static final OutOfServiceState INSTANCE = new OutOfServiceState();

    private OutOfServiceState() {
    }

    @Override
    public AtmStatus status() {
        return AtmStatus.OUT_OF_SERVICE;
    }

    @Override
    public void startMaintenance(Atm atm) {
        // already offline: nothing to do
    }

    @Override
    public void refill(Atm atm, Map<Integer, Integer> notes) {
        atm.dispenser().accept(notes);
    }

    @Override
    public void finishMaintenance(Atm atm) {
        if (atm.dispenser().isEmpty()) {
            throw AtmException.declined("Load cash before bringing the ATM back into service");
        }
        atm.clearHardwareFault();
        atm.transitionTo(IdleState.INSTANCE);
    }
}
