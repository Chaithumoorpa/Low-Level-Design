package com.lld.states.vending.machine;

import com.lld.states.vending.model.VendResult;
import com.lld.states.vending.model.VendingException;

import java.util.Map;

/**
 * State pattern: every button and slot on the machine, "not allowed" by default. Each state
 * overrides only what makes sense for it. States hold no data and are shared instances.
 */
interface VendingState {

    VendingStatus status();

    default void insertCoin(VendingMachine m, int denomination) {
        throw notHere("insert coins");
    }

    default VendResult selectProduct(VendingMachine m, String slotCode) {
        throw notHere("select a product");
    }

    default Map<Integer, Integer> cancel(VendingMachine m) {
        throw notHere("cancel");
    }

    default void startMaintenance(VendingMachine m) {
        throw notHere("start maintenance");
    }

    default void restock(VendingMachine m, String slotCode, int count) {
        throw notHere("restock");
    }

    default void setPrice(VendingMachine m, String slotCode, int price) {
        throw notHere("change prices");
    }

    default void loadCoins(VendingMachine m, Map<Integer, Integer> coins) {
        throw notHere("load coins");
    }

    default Map<Integer, Integer> collectCash(VendingMachine m) {
        throw notHere("collect cash");
    }

    default void finishMaintenance(VendingMachine m) {
        throw notHere("finish maintenance");
    }

    private VendingException notHere(String action) {
        return VendingException.invalidState(action, status().name());
    }
}
