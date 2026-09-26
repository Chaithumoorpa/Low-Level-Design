package com.lld.states.elevator.model;

/** Travel direction of a car or a hall call. IDLE only applies to cars. */
public enum Direction {
    UP(1),
    DOWN(-1),
    IDLE(0);

    private final int delta;

    Direction(int delta) {
        this.delta = delta;
    }

    public int delta() {
        return delta;
    }

    public Direction opposite() {
        return this == UP ? DOWN : this == DOWN ? UP : IDLE;
    }

    public static Direction between(int from, int to) {
        return to > from ? UP : to < from ? DOWN : IDLE;
    }
}
