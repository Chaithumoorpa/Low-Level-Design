package com.lld.states.elevator.model;

/** An up or down button pressed on a floor. */
public record HallCall(int floor, Direction direction) {

    public HallCall {
        if (direction == Direction.IDLE) {
            throw new IllegalArgumentException("A hall call is either UP or DOWN");
        }
    }

    @Override
    public String toString() {
        return floor + (direction == Direction.UP ? "^" : "v");
    }
}
