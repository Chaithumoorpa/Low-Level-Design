package com.lld.states.vending.machine;

import com.lld.states.vending.model.VendResult;

import java.util.Map;

/** Coins are in escrow. The customer can add more, buy, or cancel and get the same coins back. */
final class HasMoneyState implements VendingState {

    static final HasMoneyState INSTANCE = new HasMoneyState();

    private HasMoneyState() {
    }

    @Override
    public VendingStatus status() {
        return VendingStatus.HAS_MONEY;
    }

    @Override
    public void insertCoin(VendingMachine m, int denomination) {
        m.acceptCoin(denomination);
    }

    @Override
    public VendResult selectProduct(VendingMachine m, String slotCode) {
        return m.performVend(slotCode);
    }

    @Override
    public Map<Integer, Integer> cancel(VendingMachine m) {
        Map<Integer, Integer> refund = m.releaseEscrow();
        m.transitionTo(IdleState.INSTANCE);
        return refund;
    }
}
