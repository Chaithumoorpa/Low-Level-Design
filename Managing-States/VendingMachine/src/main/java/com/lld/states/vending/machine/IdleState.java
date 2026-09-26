package com.lld.states.vending.machine;

import com.lld.states.vending.model.Money;
import com.lld.states.vending.model.Slot;
import com.lld.states.vending.model.VendResult;
import com.lld.states.vending.model.VendingException;

/** Nothing inserted yet. Selecting a product just shows its price. */
final class IdleState implements VendingState {

    static final IdleState INSTANCE = new IdleState();

    private IdleState() {
    }

    @Override
    public VendingStatus status() {
        return VendingStatus.IDLE;
    }

    @Override
    public void insertCoin(VendingMachine m, int denomination) {
        m.acceptCoin(denomination);
        m.transitionTo(HasMoneyState.INSTANCE);
    }

    @Override
    public VendResult selectProduct(VendingMachine m, String slotCode) {
        Slot slot = m.slot(slotCode);
        throw VendingException.declined(slot.isSoldOut()
                ? slot.product().name() + " is sold out"
                : slot.product().name() + " costs " + Money.format(slot.product().price()) + ". Please insert coins.");
    }

    @Override
    public void startMaintenance(VendingMachine m) {
        m.transitionTo(OutOfServiceState.INSTANCE);
    }
}
