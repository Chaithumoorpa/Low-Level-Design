package com.lld.states.elevator.dispatch;

import com.lld.states.elevator.car.ElevatorCar;
import com.lld.states.elevator.model.HallCall;

import java.util.List;
import java.util.Optional;

/**
 * Strategy: which car answers a hall call. Only cars that currently accept calls are offered
 * (not full, not in maintenance or emergency). Empty means "no car available right now".
 */
public interface DispatchStrategy {

    Optional<ElevatorCar> choose(HallCall call, List<ElevatorCar> candidates);
}
