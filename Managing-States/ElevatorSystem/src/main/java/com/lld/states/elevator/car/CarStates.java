package com.lld.states.elevator.car;

import com.lld.states.elevator.model.Direction;

/** The six car states. Each is tiny; the shared logic lives in {@link ElevatorCar}. */
final class CarStates {

    private CarStates() {
    }

    /** No work. Wakes up when a stop is assigned: opens here, or starts moving towards it. */
    static final CarState IDLE = new CarState() {
        public CarStatus status() {
            return CarStatus.IDLE;
        }

        public void tick(ElevatorCar car) {
            if (!car.hasWork()) {
                return;
            }
            if (car.hasServableStopHere()) {
                car.openDoors();
            } else {
                car.setDirection(car.chooseDirection());
                car.transitionTo(car.direction() == Direction.UP ? MOVING_UP : MOVING_DOWN);
            }
        }
    };

    static final CarState MOVING_UP = moving(Direction.UP, CarStatus.MOVING_UP);
    static final CarState MOVING_DOWN = moving(Direction.DOWN, CarStatus.MOVING_DOWN);

    /** One floor per tick; stops where the LOOK rules say so. */
    private static CarState moving(Direction direction, CarStatus status) {
        return new CarState() {
            public CarStatus status() {
                return status;
            }

            public void tick(ElevatorCar car) {
                car.moveOneFloor(direction);
                if (car.shouldStopHere()) {
                    car.openDoors();
                } else {
                    car.reconsiderWhileMoving();
                }
            }
        };
    }

    /** Passengers step in and out; when the timer runs out, decide what to do next. */
    static final CarState DOORS_OPEN = new CarState() {
        public CarStatus status() {
            return CarStatus.DOORS_OPEN;
        }

        public void tick(ElevatorCar car) {
            if (car.countDownDoors() > 0) {
                return;
            }
            car.afterDoorsClose();
        }
    };

    /** Out of service for staff. Keeps no calls; ticks do nothing. */
    static final CarState MAINTENANCE = new CarState() {
        public CarStatus status() {
            return CarStatus.MAINTENANCE;
        }

        public void tick(ElevatorCar car) {
            // parked
        }

        public boolean acceptsCalls() {
            return false;
        }
    };

    /** Fire recall: go straight to the lobby, ignore every call, open, and stay there. */
    static final CarState EMERGENCY = new CarState() {
        public CarStatus status() {
            return CarStatus.EMERGENCY;
        }

        public void tick(ElevatorCar car) {
            car.emergencyStep();
        }

        public boolean acceptsCalls() {
            return false;
        }
    };
}
