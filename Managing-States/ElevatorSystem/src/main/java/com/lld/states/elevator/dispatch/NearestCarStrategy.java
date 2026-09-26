package com.lld.states.elevator.dispatch;

import com.lld.states.elevator.car.ElevatorCar;
import com.lld.states.elevator.model.Direction;
import com.lld.states.elevator.model.HallCall;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the car that would reach the caller soonest, estimated in floors:
 *
 * <ul>
 *   <li><b>idle</b>: straight distance;</li>
 *   <li><b>already heading towards the caller in the caller's direction</b>: straight distance
 *       (it will stop on the way);</li>
 *   <li><b>otherwise</b>: it must first reach its turning floor, then come back:
 *       |turn − here| + |turn − caller|.</li>
 * </ul>
 * Current load is added as a small tie-breaker so busy cars are slightly less attractive.
 */
public class NearestCarStrategy implements DispatchStrategy {

    @Override
    public Optional<ElevatorCar> choose(HallCall call, List<ElevatorCar> candidates) {
        return candidates.stream().min(Comparator.comparingInt((ElevatorCar car) -> cost(car, call))
                .thenComparingInt(ElevatorCar::id));
    }

    static int cost(ElevatorCar car, HallCall call) {
        int here = car.floor();
        int target = call.floor();
        Direction d = car.direction();
        int floors;
        if (car.isIdle() || d == Direction.IDLE) {
            floors = Math.abs(here - target);
        } else {
            boolean ahead = d == Direction.UP ? target >= here : target <= here;
            if (ahead && d == call.direction()) {
                floors = Math.abs(target - here);
            } else {
                int turn = car.turningFloor();
                floors = Math.abs(turn - here) + Math.abs(turn - target);
            }
        }
        return floors * 2 + car.load();
    }
}
