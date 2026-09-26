package com.lld.states.vending.machine;

import com.lld.states.vending.model.VendResult;

/** Observer: display, telemetry ("slot A1 is empty"), sales reporting. */
public interface VendingListener {

    default void onStateChange(VendingStatus from, VendingStatus to) {
    }

    default void onBalanceChange(long balance) {
    }

    default void onSale(VendResult result) {
    }

    default void onSoldOut(String slotCode) {
    }
}
