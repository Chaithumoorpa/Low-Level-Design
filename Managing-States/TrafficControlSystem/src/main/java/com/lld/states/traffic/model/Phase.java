package com.lld.states.traffic.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * A group of approaches that may have green at the same time. North-South and East-West cross
 * each other, so they must never be green together: that is the one safety rule of the intersection.
 */
public enum Phase {
    NORTH_SOUTH(EnumSet.of(Approach.NORTH, Approach.SOUTH)),
    EAST_WEST(EnumSet.of(Approach.EAST, Approach.WEST));

    private final Set<Approach> approaches;

    Phase(Set<Approach> approaches) {
        this.approaches = approaches;
    }

    public Set<Approach> approaches() {
        return approaches;
    }

    public boolean includes(Approach approach) {
        return approaches.contains(approach);
    }

    public Phase next() {
        return this == NORTH_SOUTH ? EAST_WEST : NORTH_SOUTH;
    }

    public static Phase of(Approach approach) {
        return NORTH_SOUTH.includes(approach) ? NORTH_SOUTH : EAST_WEST;
    }
}
