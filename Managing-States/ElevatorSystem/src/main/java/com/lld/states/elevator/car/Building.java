package com.lld.states.elevator.car;

import com.lld.states.elevator.model.Direction;
import com.lld.states.elevator.model.HallCall;
import com.lld.states.elevator.model.Passenger;

import java.util.List;

/**
 * What a car needs from the building: people waiting at floors, somewhere to report arrivals, and
 * a way to hand back hall calls it can no longer serve. Implemented by the ElevatorSystem.
 */
public interface Building {

    /** People waiting at {@code floor} to go {@code direction} step in, up to {@code space} of them. */
    List<Passenger> board(ElevatorCar car, int floor, Direction direction, int space);

    void delivered(Passenger passenger);

    void evacuated(Passenger passenger);

    /** A call this car will not serve after all (maintenance): assign it to another car. */
    void reassign(HallCall call);

    long now();
}
