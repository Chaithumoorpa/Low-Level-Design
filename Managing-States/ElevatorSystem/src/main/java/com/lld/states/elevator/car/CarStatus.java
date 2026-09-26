package com.lld.states.elevator.car;

/** What the car's indicator panel shows; one value per state class. */
public enum CarStatus {
    IDLE,
    MOVING_UP,
    MOVING_DOWN,
    DOORS_OPEN,
    MAINTENANCE,
    EMERGENCY
}
