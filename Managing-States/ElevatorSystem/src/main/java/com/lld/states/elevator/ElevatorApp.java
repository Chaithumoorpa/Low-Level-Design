package com.lld.states.elevator;

import com.lld.states.elevator.dispatch.DispatchStrategy;
import com.lld.states.elevator.dispatch.NearestCarStrategy;
import com.lld.states.elevator.dispatch.RoundRobinStrategy;
import com.lld.states.elevator.model.Passenger;
import com.lld.states.elevator.system.ElevatorSystem;

import java.util.Random;

/** Demo: a traced single-car LOOK run, a dispatcher comparison, then a fire recall. */
public class ElevatorApp {

    public static void main(String[] args) {
        traceSingleCar();
        compareDispatchers();
        fireRecall();
    }

    private static void traceSingleCar() {
        System.out.println("1) One car, floors 0-9, doors open 1 tick. LOOK in action.");
        ElevatorSystem building = new ElevatorSystem(0, 9, 1, 8, 1, new NearestCarStrategy());
        building.onCarStatusChange((car, status) ->
                System.out.printf("   t=%-3d car%d %-11s at floor %d  riders=%d stops=%s%n",
                        building.currentTick(), car.id(), status, car.floor(), car.load(), car.carStops()));
        building.requestRide(0, 7);           // going up to 7
        building.requestRide(4, 1);           // waiting at 4 going DOWN: skipped on the way up
        building.requestRide(5, 9);           // waiting at 5 going UP: picked up on the way
        building.runUntilAllDelivered(200);
        for (Passenger p : building.delivered()) {
            System.out.printf("   %s waited %d, rode %d%n", p, p.waitTime(), p.rideTime());
        }
    }

    private static void compareDispatchers() {
        System.out.println();
        System.out.println("2) 4 cars, floors 0-19, ~300 random passengers over 600 ticks (same traffic for both)");
        for (DispatchStrategy strategy : new DispatchStrategy[]{new RoundRobinStrategy(), new NearestCarStrategy()}) {
            ElevatorSystem building = new ElevatorSystem(0, 19, 4, 8, 2, strategy);
            Random random = new Random(42);
            for (int tick = 0; tick < 600; tick++) {
                if (random.nextInt(2) == 0) {
                    int from = random.nextInt(3) == 0 ? 0 : random.nextInt(20);   // lobby-heavy traffic
                    int to;
                    do {
                        to = random.nextInt(20);
                    } while (to == from);
                    building.requestRide(from, to);
                }
                building.step();
            }
            building.runUntilAllDelivered(5000);
            System.out.printf("   %-20s %s%n", strategy.getClass().getSimpleName(), building.stats());
        }
    }

    private static void fireRecall() {
        System.out.println();
        System.out.println("3) Fire alarm while cars are busy");
        ElevatorSystem building = new ElevatorSystem(0, 9, 2, 8, 1, new NearestCarStrategy());
        building.requestRide(0, 9);
        building.requestRide(0, 6);
        for (int i = 0; i < 6; i++) {
            building.step();
        }
        System.out.println("   before: " + building.cars());
        building.fireAlarm();
        for (int i = 0; i < 12; i++) {
            building.step();
        }
        System.out.println("   after:  " + building.cars());
        System.out.println("   evacuated at the lobby: " + building.evacuated());
        try {
            building.requestRide(3, 0);
        } catch (IllegalStateException e) {
            System.out.println("   new request: " + e.getMessage());
        }
    }
}
