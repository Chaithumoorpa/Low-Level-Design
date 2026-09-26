package com.lld.states.elevator.model;

/**
 * Someone travelling from one floor to another. Timestamps are simulation ticks and let the system
 * report waiting time (request → boarding) and ride time (boarding → arrival).
 */
public final class Passenger {

    private final int id;
    private final int from;
    private final int to;
    private final long requestedAt;
    private long boardedAt = -1;
    private long arrivedAt = -1;
    private int carId = -1;

    public Passenger(int id, int from, int to, long requestedAt) {
        if (from == to) {
            throw new IllegalArgumentException("Origin and destination must differ");
        }
        this.id = id;
        this.from = from;
        this.to = to;
        this.requestedAt = requestedAt;
    }

    public void board(int carId, long tick) {
        this.carId = carId;
        this.boardedAt = tick;
    }

    public void arrive(long tick) {
        this.arrivedAt = tick;
    }

    public int id() {
        return id;
    }

    public int from() {
        return from;
    }

    public int to() {
        return to;
    }

    public Direction direction() {
        return Direction.between(from, to);
    }

    public long waitTime() {
        return boardedAt - requestedAt;
    }

    public long rideTime() {
        return arrivedAt - boardedAt;
    }

    public boolean hasArrived() {
        return arrivedAt >= 0;
    }

    public int carId() {
        return carId;
    }

    @Override
    public String toString() {
        return "P" + id + "(" + from + "->" + to + ")";
    }
}
