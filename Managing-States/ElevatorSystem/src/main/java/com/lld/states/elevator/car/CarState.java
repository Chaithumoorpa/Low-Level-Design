package com.lld.states.elevator.car;

/**
 * State pattern for one car. Every simulation tick is forwarded to the current state, which moves
 * the car, runs the door timer, or does nothing. Requests are accepted or ignored per state.
 */
interface CarState {

    CarStatus status();

    void tick(ElevatorCar car);

    /** Whether the dispatcher may give this car new hall calls. */
    default boolean acceptsCalls() {
        return true;
    }
}
