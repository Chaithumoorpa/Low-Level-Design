package com.lld.states.elevator.system;

import com.lld.states.elevator.car.Building;
import com.lld.states.elevator.car.CarStatus;
import com.lld.states.elevator.car.ElevatorCar;
import com.lld.states.elevator.dispatch.DispatchStrategy;
import com.lld.states.elevator.model.Direction;
import com.lld.states.elevator.model.HallCall;
import com.lld.states.elevator.model.Passenger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The building controller (facade): owns the cars, the hall buttons and the waiting passengers, and
 * advances the simulation one tick at a time. One tick = time to travel one floor.
 *
 * <p>Hall calls are assigned once, by the {@link DispatchStrategy}. A call no car can take right now
 * (all full or offline) waits in a pending list and is retried every tick, so nobody is forgotten.
 */
public final class ElevatorSystem implements Building {

    private final int lowestFloor;
    private final int highestFloor;
    private final DispatchStrategy dispatcher;
    private final List<ElevatorCar> cars = new ArrayList<>();
    private final Map<HallCall, Deque<Passenger>> waiting = new HashMap<>();
    private final Map<HallCall, ElevatorCar> assignments = new HashMap<>();
    private final Set<HallCall> unassigned = new LinkedHashSet<>();
    private final List<Passenger> delivered = new ArrayList<>();
    private final List<Passenger> evacuated = new ArrayList<>();
    private final List<BiConsumer<ElevatorCar, CarStatus>> carListeners = new ArrayList<>();

    private long now;
    private int nextPassengerId = 1;
    private boolean fireMode;

    public ElevatorSystem(int lowestFloor, int highestFloor, int carCount, int capacity, int doorOpenTicks,
                          DispatchStrategy dispatcher) {
        if (highestFloor <= lowestFloor || carCount < 1) {
            throw new IllegalArgumentException("Need at least two floors and one car");
        }
        this.lowestFloor = lowestFloor;
        this.highestFloor = highestFloor;
        this.dispatcher = dispatcher;
        for (int i = 0; i < carCount; i++) {
            ElevatorCar car = new ElevatorCar(i + 1, lowestFloor, lowestFloor, capacity, doorOpenTicks, this);
            car.onStatusChange((c, s) -> carListeners.forEach(l -> l.accept(c, s)));
            cars.add(car);
        }
    }

    public void onCarStatusChange(BiConsumer<ElevatorCar, CarStatus> listener) {
        carListeners.add(listener);
    }

    // ------------------------------------------------------------------ people and buttons

    /** Someone arrives at {@code from} and presses the hall button towards {@code to}. */
    public Passenger requestRide(int from, int to) {
        if (fireMode) {
            throw new IllegalStateException("Fire alarm: elevators are out of service, use the stairs");
        }
        checkFloor(from);
        checkFloor(to);
        Passenger p = new Passenger(nextPassengerId++, from, to, now);
        HallCall call = new HallCall(from, p.direction());
        waiting.computeIfAbsent(call, c -> new ArrayDeque<>()).add(p);
        pressHallButton(call);
        return p;
    }

    private void pressHallButton(HallCall call) {
        if (assignments.containsKey(call) || unassigned.contains(call)) {
            return;                                           // button already lit
        }
        List<ElevatorCar> candidates = cars.stream().filter(ElevatorCar::acceptsCalls).toList();
        dispatcher.choose(call, candidates).ifPresentOrElse(car -> {
            assignments.put(call, car);
            car.assign(call);
        }, () -> unassigned.add(call));
    }

    // ------------------------------------------------------------------ simulation

    /** Advances time by one tick: every car acts once, then pending calls are retried. */
    public void step() {
        now++;
        cars.forEach(ElevatorCar::tick);
        if (!unassigned.isEmpty() && !fireMode) {
            List<HallCall> retry = new ArrayList<>(unassigned);
            unassigned.clear();
            retry.forEach(this::pressHallButton);
        }
    }

    /** Runs until everyone who asked has arrived (or the limit is hit). @return ticks used */
    public long runUntilAllDelivered(long maxTicks) {
        long start = now;
        while (hasOutstandingWork() && now - start < maxTicks) {
            step();
        }
        return now - start;
    }

    public boolean hasOutstandingWork() {
        boolean anyoneWaiting = waiting.values().stream().anyMatch(q -> !q.isEmpty());
        boolean anyoneRiding = cars.stream().anyMatch(c -> c.load() > 0);
        return anyoneWaiting || anyoneRiding;
    }

    // ------------------------------------------------------------------ Building (called by cars)

    @Override
    public List<Passenger> board(ElevatorCar car, int floor, Direction direction, int space) {
        HallCall call = new HallCall(floor, direction);
        assignments.remove(call);
        Deque<Passenger> queue = waiting.getOrDefault(call, new ArrayDeque<>());
        List<Passenger> boarding = new ArrayList<>();
        while (!queue.isEmpty() && boarding.size() < space) {
            boarding.add(queue.poll());
        }
        if (!queue.isEmpty()) {
            unassigned.add(call);                             // car full: the rest press the button again
        }
        return boarding;
    }

    @Override
    public void delivered(Passenger passenger) {
        delivered.add(passenger);
    }

    @Override
    public void evacuated(Passenger passenger) {
        evacuated.add(passenger);
    }

    @Override
    public void reassign(HallCall call) {
        assignments.remove(call);
        unassigned.add(call);
    }

    @Override
    public long now() {
        return now;
    }

    // ------------------------------------------------------------------ operations

    public void requestMaintenance(int carId) {
        car(carId).requestMaintenance();
    }

    public void endMaintenance(int carId) {
        car(carId).endMaintenance();
    }

    /** Every car returns to the lobby and opens; waiting passengers are told to use the stairs. */
    public void fireAlarm() {
        fireMode = true;
        assignments.clear();
        unassigned.clear();
        waiting.values().forEach(Deque::clear);
        cars.forEach(ElevatorCar::fireRecall);
    }

    public void resetFireAlarm() {
        fireMode = false;
        cars.forEach(ElevatorCar::resetAfterEmergency);
    }

    // ------------------------------------------------------------------ queries

    public List<ElevatorCar> cars() {
        return Collections.unmodifiableList(cars);
    }

    public ElevatorCar car(int id) {
        return cars.stream().filter(c -> c.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No car " + id));
    }

    public List<Passenger> delivered() {
        return Collections.unmodifiableList(delivered);
    }

    public List<Passenger> evacuated() {
        return Collections.unmodifiableList(evacuated);
    }

    public ElevatorCar assignedCar(HallCall call) {
        return assignments.get(call);
    }

    public long currentTick() {
        return now;
    }

    public SystemStats stats() {
        long maxWait = delivered.stream().mapToLong(Passenger::waitTime).max().orElse(0);
        double avgWait = delivered.stream().mapToLong(Passenger::waitTime).average().orElse(0);
        double avgRide = delivered.stream().mapToLong(Passenger::rideTime).average().orElse(0);
        long travel = cars.stream().mapToLong(ElevatorCar::floorsTravelled).sum();
        return new SystemStats(delivered.size(), avgWait, maxWait, avgRide, travel);
    }

    private void checkFloor(int floor) {
        if (floor < lowestFloor || floor > highestFloor) {
            throw new IllegalArgumentException("Floor " + floor + " is outside " + lowestFloor + ".." + highestFloor);
        }
    }
}
