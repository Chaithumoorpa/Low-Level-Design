package com.lld.states.coffee.machine;

public enum MachineStatus {
    IDLE,             // "Choose a drink"
    SELECTING,        // drink chosen: customise, pay or cancel
    BREWING,          // "Preparing your drink..."
    NEEDS_CLEANING,   // cleaning cycle due after N cups
    OUT_OF_SERVICE    // maintenance, ingredients empty, or a fault
}
