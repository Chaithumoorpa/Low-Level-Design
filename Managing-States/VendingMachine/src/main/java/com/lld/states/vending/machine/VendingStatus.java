package com.lld.states.vending.machine;

/** What the display shows; one value per state class. */
public enum VendingStatus {
    IDLE,             // "Insert coins or select a product to see its price"
    HAS_MONEY,        // "Balance $0.75 — select a product"
    DISPENSING,       // "Please wait..."
    OUT_OF_SERVICE    // "Out of service" (maintenance, sold out, or a fault)
}
