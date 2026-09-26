package com.lld.states.vending.machine;

import com.lld.states.vending.model.VendingException;

import java.util.Map;

/** Door open for staff, everything sold out, or a jam. Only operator actions are allowed. */
final class OutOfServiceState implements VendingState {

    static final OutOfServiceState INSTANCE = new OutOfServiceState();

    private OutOfServiceState() {
    }

    @Override
    public VendingStatus status() {
        return VendingStatus.OUT_OF_SERVICE;
    }

    @Override
    public void startMaintenance(VendingMachine m) {
        // already open
    }

    @Override
    public void restock(VendingMachine m, String slotCode, int count) {
        m.slot(slotCode).restock(count);
    }

    @Override
    public void setPrice(VendingMachine m, String slotCode, int price) {
        if (price <= 0) {
            throw VendingException.declined("Price must be positive");
        }
        m.slot(slotCode).setPrice(price);
    }

    @Override
    public void loadCoins(VendingMachine m, Map<Integer, Integer> coins) {
        m.coinBox().add(coins);
    }

    @Override
    public Map<Integer, Integer> collectCash(VendingMachine m) {
        return m.coinBox().removeAll();
    }

    @Override
    public void finishMaintenance(VendingMachine m) {
        if (m.allSoldOut()) {
            throw VendingException.declined("Restock at least one slot before going back into service");
        }
        m.clearFault();
        m.transitionTo(IdleState.INSTANCE);
    }
}
