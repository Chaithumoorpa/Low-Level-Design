package com.lld.states.vending.machine;

/** The motor that pushes a product out of a slot. Can jam; tests simulate that. */
@FunctionalInterface
public interface ProductDispenser {

    void dispense(String slotCode);

    ProductDispenser ALWAYS_WORKS = code -> { };
}
