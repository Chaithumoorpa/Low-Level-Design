package com.lld.states.elevator.car;

import com.lld.states.elevator.model.Direction;
import com.lld.states.elevator.model.HallCall;
import com.lld.states.elevator.model.Passenger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * One elevator car: the <b>context</b> of the State pattern, and the home of the LOOK algorithm.
 *
 * <p><b>LOOK</b>: keep going in the current direction while there is anything to do further along
 * (a passenger's destination, or a hall call); stop on the way for destinations and for hall calls
 * in the SAME direction; turn around only when nothing is left ahead.
 *
 * <p>Stops are kept in three sorted sets: {@code carStops} (buttons pressed inside the car),
 * {@code upCalls} and {@code downCalls} (hall buttons assigned to this car).
 */
public final class ElevatorCar {

    private final int id;
    private final int lobby;
    private final int capacity;
    private final int doorOpenTicks;
    private final Building building;
    private final List<BiConsumer<ElevatorCar, CarStatus>> listeners = new ArrayList<>();

    private final NavigableSet<Integer> carStops = new TreeSet<>();
    private final NavigableSet<Integer> upCalls = new TreeSet<>();
    private final NavigableSet<Integer> downCalls = new TreeSet<>();
    private final List<Passenger> riders = new ArrayList<>();

    private CarState state = CarStates.IDLE;
    private int floor;
    private Direction direction = Direction.IDLE;
    private int doorTimer;
    private boolean maintenanceRequested;
    private boolean evacuated;
    private int floorsTravelled;

    public ElevatorCar(int id, int startFloor, int lobby, int capacity, int doorOpenTicks, Building building) {
        if (capacity < 1 || doorOpenTicks < 1) {
            throw new IllegalArgumentException("Capacity and door time must be positive");
        }
        this.id = id;
        this.floor = startFloor;
        this.lobby = lobby;
        this.capacity = capacity;
        this.doorOpenTicks = doorOpenTicks;
        this.building = building;
    }

    public void onStatusChange(BiConsumer<ElevatorCar, CarStatus> listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ commands from the system

    public void tick() {
        state.tick(this);
    }

    public void assign(HallCall call) {
        if (!state.acceptsCalls() || maintenanceRequested) {
            throw new IllegalStateException("Car " + id + " is not taking calls");
        }
        (call.direction() == Direction.UP ? upCalls : downCalls).add(call.floor());
    }

    /** Finish delivering current riders, take no new calls, then park for maintenance. */
    public void requestMaintenance() {
        maintenanceRequested = true;
        handBackHallCalls();
        if (state == CarStates.IDLE) {
            transitionTo(CarStates.MAINTENANCE);
        }
    }

    public void endMaintenance() {
        if (state != CarStates.MAINTENANCE) {
            throw new IllegalStateException("Car " + id + " is not in maintenance");
        }
        maintenanceRequested = false;
        transitionTo(CarStates.IDLE);
    }

    /** Fire recall: drop every request and head for the lobby. */
    public void fireRecall() {
        upCalls.clear();
        downCalls.clear();
        carStops.clear();
        evacuated = false;
        direction = Direction.between(floor, lobby);
        transitionTo(CarStates.EMERGENCY);
    }

    public void resetAfterEmergency() {
        if (state != CarStates.EMERGENCY) {
            throw new IllegalStateException("Car " + id + " is not in emergency mode");
        }
        direction = Direction.IDLE;
        transitionTo(maintenanceRequested ? CarStates.MAINTENANCE : CarStates.IDLE);
    }

    // ------------------------------------------------------------------ queries (dispatcher, tests)

    public int id() {
        return id;
    }

    public int floor() {
        return floor;
    }

    public Direction direction() {
        return direction;
    }

    public CarStatus status() {
        return state.status();
    }

    public boolean acceptsCalls() {
        return state.acceptsCalls() && !maintenanceRequested && riders.size() < capacity;
    }

    public int load() {
        return riders.size();
    }

    public int capacity() {
        return capacity;
    }

    public boolean isIdle() {
        return state == CarStates.IDLE && !hasWork();
    }

    public int floorsTravelled() {
        return floorsTravelled;
    }

    /** The furthest floor this car must reach in its current direction (where it will turn). */
    public int turningFloor() {
        Integer far = direction == Direction.DOWN ? lowestTarget() : highestTarget();
        return far == null ? floor : far;
    }

    public List<Passenger> riders() {
        return Collections.unmodifiableList(riders);
    }

    public NavigableSet<Integer> carStops() {
        return Collections.unmodifiableNavigableSet(carStops);
    }

    // ------------------------------------------------------------------ helpers for the states

    void transitionTo(CarState next) {
        state = next;
        listeners.forEach(l -> l.accept(this, next.status()));
    }

    void setDirection(Direction direction) {
        this.direction = direction;
    }

    boolean hasWork() {
        return !carStops.isEmpty() || !upCalls.isEmpty() || !downCalls.isEmpty();
    }

    void moveOneFloor(Direction d) {
        floor += d.delta();
        floorsTravelled++;
    }

    /**
     * Called after each floor of movement when the car should not stop: if nothing is left ahead
     * (e.g. its calls were handed back), reverse or go idle instead of running past the last floor.
     */
    void reconsiderWhileMoving() {
        boolean nothingAhead = direction == Direction.UP ? !hasTargetsAbove() : !hasTargetsBelow();
        if (!nothingAhead) {
            return;
        }
        direction = chooseDirection();
        if (direction == Direction.IDLE) {
            transitionTo(maintenanceRequested && riders.isEmpty() ? CarStates.MAINTENANCE : CarStates.IDLE);
        } else {
            transitionTo(direction == Direction.UP ? CarStates.MOVING_UP : CarStates.MOVING_DOWN);
        }
    }

    /** LOOK stopping rule while moving. */
    boolean shouldStopHere() {
        if (carStops.contains(floor)) {
            return true;
        }
        if (direction == Direction.UP && upCalls.contains(floor)) {
            return true;
        }
        if (direction == Direction.DOWN && downCalls.contains(floor)) {
            return true;
        }
        boolean nothingAhead = direction == Direction.UP ? !hasTargetsAbove() : !hasTargetsBelow();
        return nothingAhead && (upCalls.contains(floor) || downCalls.contains(floor));   // turning point
    }

    /** Whether opening the doors here would actually do something (used when idle or reconsidering). */
    boolean hasServableStopHere() {
        return carStops.contains(floor) || serviceDirectionHere() != Direction.IDLE;
    }

    /** Direction the car should take next according to LOOK (keep going if anything is ahead). */
    Direction chooseDirection() {
        boolean above = hasTargetsAbove();
        boolean below = hasTargetsBelow();
        if (direction == Direction.UP) {
            return above ? Direction.UP : below ? Direction.DOWN : Direction.IDLE;
        }
        if (direction == Direction.DOWN) {
            return below ? Direction.DOWN : above ? Direction.UP : Direction.IDLE;
        }
        Integer up = higherTarget();
        Integer down = lowerTarget();
        if (up == null && down == null) {
            return Direction.IDLE;
        }
        if (down == null) {
            return Direction.UP;
        }
        if (up == null) {
            return Direction.DOWN;
        }
        return up - floor <= floor - down ? Direction.UP : Direction.DOWN;   // nearest first
    }

    /**
     * Doors open: riders for this floor leave; the hall call matching the direction the car will go
     * next is cleared, and those passengers step in and press their destinations.
     */
    void openDoors() {
        List<Passenger> leaving = new ArrayList<>();
        for (Passenger p : riders) {
            if (p.to() == floor) {
                leaving.add(p);
            }
        }
        riders.removeAll(leaving);
        leaving.forEach(p -> {
            p.arrive(building.now());
            building.delivered(p);
        });
        carStops.remove(floor);

        Direction serve = serviceDirectionHere();
        if (serve != Direction.IDLE) {
            (serve == Direction.UP ? upCalls : downCalls).remove(floor);
            for (Passenger p : building.board(this, floor, serve, capacity - riders.size())) {
                p.board(id, building.now());
                riders.add(p);
                carStops.add(p.to());
            }
            direction = serve;
        } else {
            direction = chooseDirection();
        }
        doorTimer = doorOpenTicks;
        transitionTo(CarStates.DOORS_OPEN);
    }

    int countDownDoors() {
        return --doorTimer;
    }

    void afterDoorsClose() {
        if (maintenanceRequested && riders.isEmpty()) {
            direction = Direction.IDLE;
            transitionTo(CarStates.MAINTENANCE);
            return;
        }
        if (hasServableStopHere()) {
            openDoors();                                    // someone pressed the button right here
            return;
        }
        direction = chooseDirection();
        if (direction == Direction.IDLE) {
            transitionTo(CarStates.IDLE);
        } else {
            transitionTo(direction == Direction.UP ? CarStates.MOVING_UP : CarStates.MOVING_DOWN);
        }
    }

    void emergencyStep() {
        if (floor != lobby) {
            moveOneFloor(Direction.between(floor, lobby));
            return;
        }
        if (!evacuated) {
            riders.forEach(building::evacuated);
            riders.clear();
            evacuated = true;
            direction = Direction.IDLE;
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Which waiting direction to serve when stopped here: the direction the car will continue in,
     * or, if the car has nothing else to do, whichever call is waiting (up first).
     */
    private Direction serviceDirectionHere() {
        boolean above = hasTargetsAbove();
        boolean below = hasTargetsBelow();
        Direction preferred = direction == Direction.UP ? (above ? Direction.UP : below ? Direction.DOWN : Direction.IDLE)
                : direction == Direction.DOWN ? (below ? Direction.DOWN : above ? Direction.UP : Direction.IDLE)
                : Direction.IDLE;
        if ((preferred == Direction.UP || preferred == Direction.IDLE) && upCalls.contains(floor)) {
            return Direction.UP;
        }
        if ((preferred == Direction.DOWN || preferred == Direction.IDLE) && downCalls.contains(floor)) {
            return Direction.DOWN;
        }
        return Direction.IDLE;
    }

    private void handBackHallCalls() {
        List<HallCall> calls = new ArrayList<>();
        upCalls.forEach(f -> calls.add(new HallCall(f, Direction.UP)));
        downCalls.forEach(f -> calls.add(new HallCall(f, Direction.DOWN)));
        upCalls.clear();
        downCalls.clear();
        calls.forEach(building::reassign);
    }

    private boolean hasTargetsAbove() {
        return higherTarget() != null;
    }

    private boolean hasTargetsBelow() {
        return lowerTarget() != null;
    }

    private Integer higherTarget() {
        return min(carStops.higher(floor), upCalls.higher(floor), downCalls.higher(floor));
    }

    private Integer lowerTarget() {
        return max(carStops.lower(floor), upCalls.lower(floor), downCalls.lower(floor));
    }

    private Integer highestTarget() {
        return max(last(carStops), last(upCalls), last(downCalls));
    }

    private Integer lowestTarget() {
        return min(first(carStops), first(upCalls), first(downCalls));
    }

    private static Integer first(NavigableSet<Integer> s) {
        return s.isEmpty() ? null : s.first();
    }

    private static Integer last(NavigableSet<Integer> s) {
        return s.isEmpty() ? null : s.last();
    }

    private static Integer min(Integer... values) {
        Integer best = null;
        for (Integer v : values) {
            if (v != null && (best == null || v < best)) {
                best = v;
            }
        }
        return best;
    }

    private static Integer max(Integer... values) {
        Integer best = null;
        for (Integer v : values) {
            if (v != null && (best == null || v > best)) {
                best = v;
            }
        }
        return best;
    }

    @Override
    public String toString() {
        return "Car" + id + "[floor " + floor + ", " + status() + ", riders " + riders.size() + ", stops " + carStops + "]";
    }
}
