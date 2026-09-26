package com.lld.states.elevator;

import com.lld.states.elevator.car.CarStatus;
import com.lld.states.elevator.car.ElevatorCar;
import com.lld.states.elevator.dispatch.DispatchStrategy;
import com.lld.states.elevator.dispatch.NearestCarStrategy;
import com.lld.states.elevator.dispatch.RoundRobinStrategy;
import com.lld.states.elevator.model.Direction;
import com.lld.states.elevator.model.HallCall;
import com.lld.states.elevator.model.Passenger;
import com.lld.states.elevator.system.ElevatorSystem;
import com.lld.states.elevator.system.SystemStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ElevatorSystemTest {

    private static ElevatorSystem oneCar() {
        return new ElevatorSystem(0, 9, 1, 8, 1, new NearestCarStrategy());
    }

    /** Records the floors where car 1 opened its doors, in order. */
    private static List<Integer> recordStops(ElevatorSystem building) {
        List<Integer> stops = new ArrayList<>();
        building.onCarStatusChange((car, status) -> {
            if (status == CarStatus.DOORS_OPEN) {
                stops.add(car.floor());
            }
        });
        return stops;
    }

    // ------------------------------------------------------------------ LOOK behaviour

    @Test
    void singleRideIsDelivered() {
        ElevatorSystem b = oneCar();
        Passenger p = b.requestRide(3, 8);

        b.runUntilAllDelivered(100);

        assertTrue(p.hasArrived());
        assertEquals(4, p.waitTime(), "1 tick to start from idle + 3 floors");
        assertEquals(6, p.rideTime(), "1 tick doors + 5 floors");
    }

    @Test
    void destinationsAreVisitedInSweepOrderNotRequestOrder() {
        ElevatorSystem b = oneCar();
        List<Integer> stops = recordStops(b);
        b.requestRide(0, 8);
        b.requestRide(0, 2);
        b.requestRide(0, 5);

        b.runUntilAllDelivered(100);

        assertEquals(List.of(0, 2, 5, 8), stops);
    }

    @Test
    void oppositeDirectionCallerIsSkippedOnTheWayAndServedOnTheReturn() {
        ElevatorSystem b = oneCar();
        List<Integer> stops = recordStops(b);
        b.requestRide(0, 9);                          // car goes up to 9
        b.requestRide(4, 1);                          // DOWN call at 4: not on the way up

        b.runUntilAllDelivered(100);

        assertEquals(List.of(0, 9, 4, 1), stops);
    }

    @Test
    void sameDirectionCallerIsPickedUpOnTheWay() {
        ElevatorSystem b = oneCar();
        List<Integer> stops = recordStops(b);
        b.requestRide(0, 9);
        b.requestRide(5, 7);                          // UP call at 5: stop on the way

        b.runUntilAllDelivered(100);

        assertEquals(List.of(0, 5, 7, 9), stops);
    }

    @Test
    void carTurnsAtTheHighestDownCall() {
        ElevatorSystem b = oneCar();
        List<Integer> stops = recordStops(b);
        b.requestRide(8, 2);                          // idle car at 0 must go UP to serve a DOWN call

        b.runUntilAllDelivered(100);

        assertEquals(List.of(8, 2), stops);
        assertEquals(Direction.IDLE, b.car(1).direction());
    }

    @Test
    void carGoesIdleWhenDone() {
        ElevatorSystem b = oneCar();
        b.requestRide(0, 3);
        b.runUntilAllDelivered(100);
        b.step();
        b.step();

        assertEquals(CarStatus.IDLE, b.car(1).status());
        assertTrue(b.car(1).isIdle());
    }

    // ------------------------------------------------------------------ capacity

    @Test
    void fullCarLeavesPeopleWhoAreServedLater() {
        ElevatorSystem b = new ElevatorSystem(0, 9, 1, 2, 1, new NearestCarStrategy());   // capacity 2
        List<Passenger> group = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            group.add(b.requestRide(0, 9));
        }

        b.runUntilAllDelivered(500);

        assertTrue(group.stream().allMatch(Passenger::hasArrived));
        assertEquals(3, group.stream().map(Passenger::waitTime).distinct().count(), "3 trips of up to 2");
    }

    // ------------------------------------------------------------------ dispatching

    @Test
    void nearestIdleCarIsChosen() {
        ElevatorSystem b = new ElevatorSystem(0, 19, 2, 8, 1, new NearestCarStrategy());
        b.requestRide(0, 15);                         // send car 1 up to 15
        b.runUntilAllDelivered(100);

        b.requestRide(14, 0);                         // car 1 at 15 is closer than car 2 at 0
        assertEquals(1, b.assignedCar(new HallCall(14, Direction.DOWN)).id());
        b.requestRide(1, 5);                          // car 2 at 0 is closer
        assertEquals(2, b.assignedCar(new HallCall(1, Direction.UP)).id());
    }

    @Test
    void carAlreadyHeadingThereInTheRightDirectionIsPreferred() {
        ElevatorSystem b = new ElevatorSystem(0, 19, 2, 8, 1, new NearestCarStrategy());
        b.requestRide(0, 19);                         // car 1 heads up to 19
        b.step();
        b.step();
        b.step();                                     // car 1 moving up around floor 2
        b.car(2);                                     // car 2 idle at 0

        b.requestRide(10, 15);                        // UP at 10: car 1 passes it anyway

        assertEquals(1, b.assignedCar(new HallCall(10, Direction.UP)).id());
    }

    @Test
    void nearestCarWaitsLessThanRoundRobinOnTheSameTraffic() {
        double[] averageWait = new double[2];
        DispatchStrategy[] strategies = {new RoundRobinStrategy(), new NearestCarStrategy()};
        for (int s = 0; s < 2; s++) {
            ElevatorSystem b = new ElevatorSystem(0, 19, 4, 8, 2, strategies[s]);
            Random random = new Random(7);
            for (int tick = 0; tick < 800; tick++) {
                if (random.nextInt(2) == 0) {
                    int from = random.nextInt(20);
                    int to = (from + 1 + random.nextInt(19)) % 20;
                    b.requestRide(from, to);
                }
                b.step();
            }
            b.runUntilAllDelivered(10_000);
            averageWait[s] = b.stats().averageWait();
        }
        assertTrue(averageWait[1] < averageWait[0],
                "nearest " + averageWait[1] + " should beat round robin " + averageWait[0]);
    }

    /** Heavy random load: everyone arrives, nobody starves, nobody rides the wrong way. */
    @Test
    void randomTrafficIsAlwaysFullyDelivered() {
        for (long seed = 1; seed <= 5; seed++) {
            ElevatorSystem b = new ElevatorSystem(0, 29, 3, 6, 2, new NearestCarStrategy());
            Random random = new Random(seed);
            List<Passenger> all = new ArrayList<>();
            for (int tick = 0; tick < 1000; tick++) {
                for (int k = random.nextInt(3); k > 0; k--) {
                    int from = random.nextInt(30);
                    int to = (from + 1 + random.nextInt(29)) % 30;
                    all.add(b.requestRide(from, to));
                }
                b.step();
            }
            b.runUntilAllDelivered(20_000);

            assertTrue(all.stream().allMatch(Passenger::hasArrived), "seed " + seed + ": someone never arrived");
            SystemStats stats = b.stats();
            assertEquals(all.size(), stats.delivered());
            long minRide = all.stream().mapToLong(p -> Math.abs(p.to() - p.from())).min().orElse(0);
            assertTrue(all.stream().allMatch(p -> p.rideTime() >= Math.abs(p.to() - p.from())),
                    "a ride can't be faster than one floor per tick (min trip " + minRide + ")");
        }
    }

    // ------------------------------------------------------------------ maintenance and fire

    @Test
    void maintenanceFinishesRidersTakesNoNewCallsThenParks() {
        ElevatorSystem b = new ElevatorSystem(0, 9, 2, 8, 1, new NearestCarStrategy());
        Passenger rider = b.requestRide(0, 6);
        b.step();                                      // car 1 opens at 0, rider boards
        b.step();
        b.requestMaintenance(1);

        b.requestRide(0, 3);                           // must go to car 2
        assertEquals(2, b.assignedCar(new HallCall(0, Direction.UP)).id());
        b.runUntilAllDelivered(100);
        b.step();

        assertTrue(rider.hasArrived());
        assertEquals(CarStatus.MAINTENANCE, b.car(1).status());
        assertFalse(b.car(1).acceptsCalls());
        b.endMaintenance(1);
        assertEquals(CarStatus.IDLE, b.car(1).status());
    }

    @Test
    void maintenanceHandsAssignedCallsToAnotherCar() {
        ElevatorSystem b = new ElevatorSystem(0, 9, 2, 8, 1, new NearestCarStrategy());
        b.requestRide(5, 9);
        int first = b.assignedCar(new HallCall(5, Direction.UP)).id();

        b.requestMaintenance(first);
        b.step();

        int second = b.assignedCar(new HallCall(5, Direction.UP)).id();
        assertNotEquals(first, second);
        b.runUntilAllDelivered(100);
        assertEquals(1, b.delivered().size());
    }

    @Test
    void fireAlarmSendsEveryCarToTheLobbyAndBlocksRequests() {
        ElevatorSystem b = new ElevatorSystem(0, 9, 2, 8, 1, new NearestCarStrategy());
        b.requestRide(0, 9);
        for (int i = 0; i < 5; i++) {
            b.step();
        }
        b.requestRide(7, 2);                           // someone waiting upstairs

        b.fireAlarm();
        for (int i = 0; i < 15; i++) {
            b.step();
        }

        for (ElevatorCar car : b.cars()) {
            assertEquals(0, car.floor());
            assertEquals(CarStatus.EMERGENCY, car.status());
            assertEquals(0, car.load());
        }
        assertEquals(1, b.evacuated().size());
        assertThrows(IllegalStateException.class, () -> b.requestRide(3, 0));

        b.resetFireAlarm();
        assertEquals(CarStatus.IDLE, b.car(1).status());
        assertDoesNotThrow(() -> b.requestRide(3, 0));
    }

    @Test
    void stateTransitionsFollowTheExpectedSequence() {
        ElevatorSystem b = oneCar();
        List<CarStatus> seen = new ArrayList<>();
        b.onCarStatusChange((car, status) -> seen.add(status));
        b.requestRide(2, 4);

        b.runUntilAllDelivered(100);
        b.step();

        assertEquals(List.of(CarStatus.MOVING_UP, CarStatus.DOORS_OPEN, CarStatus.MOVING_UP,
                CarStatus.DOORS_OPEN, CarStatus.IDLE), seen);
    }

    @Test
    void invalidInput() {
        ElevatorSystem b = oneCar();
        assertThrows(IllegalArgumentException.class, () -> b.requestRide(0, 10));
        assertThrows(IllegalArgumentException.class, () -> b.requestRide(-1, 3));
        assertThrows(IllegalArgumentException.class, () -> b.requestRide(4, 4));
        assertThrows(IllegalArgumentException.class, () -> new ElevatorSystem(0, 0, 1, 8, 1, new NearestCarStrategy()));
        assertThrows(IllegalStateException.class, () -> b.endMaintenance(1));
        assertThrows(IllegalArgumentException.class, () -> new HallCall(3, Direction.IDLE));
    }
}
