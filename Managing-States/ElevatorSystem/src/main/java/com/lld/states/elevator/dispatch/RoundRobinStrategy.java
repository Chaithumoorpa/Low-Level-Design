package com.lld.states.elevator.dispatch;

import com.lld.states.elevator.car.ElevatorCar;
import com.lld.states.elevator.model.HallCall;

import java.util.List;
import java.util.Optional;

/**
 * Baseline: hand calls to cars in turn, ignoring where they are. Simple and fair in load, but it
 * sends far-away cars; the tests and demo compare it with {@link NearestCarStrategy}.
 */
public class RoundRobinStrategy implements DispatchStrategy {

    private int next;

    @Override
    public Optional<ElevatorCar> choose(HallCall call, List<ElevatorCar> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        ElevatorCar car = candidates.get(Math.floorMod(next, candidates.size()));
        next++;
        return Optional.of(car);
    }
}
