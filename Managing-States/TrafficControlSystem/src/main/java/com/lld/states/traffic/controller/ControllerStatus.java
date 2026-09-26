package com.lld.states.traffic.controller;

/** Operating mode of the controller; one value per mode class. */
public enum ControllerStatus {
    NORMAL,        // cycling phases with the timing plan
    PREEMPTION,    // emergency vehicle: its road gets green and holds it
    NIGHT_FLASH,   // main road flashing yellow, side road flashing red
    FAIL_SAFE      // conflict detected: all-way flashing red until a technician resets
}
