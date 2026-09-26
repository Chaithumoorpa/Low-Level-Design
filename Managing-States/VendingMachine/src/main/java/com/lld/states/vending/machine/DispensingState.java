package com.lld.states.vending.machine;

/**
 * The motor is running. Every action is refused (all defaults), so nothing can change the balance
 * or the stock halfway through a sale. The machine leaves this state as soon as the product drops.
 */
final class DispensingState implements VendingState {

    static final DispensingState INSTANCE = new DispensingState();

    private DispensingState() {
    }

    @Override
    public VendingStatus status() {
        return VendingStatus.DISPENSING;
    }
}
