package com.lld.states.traffic.model;

/** What a vehicle signal head can show. */
public enum LightColor {
    GREEN,
    YELLOW,
    RED,
    FLASHING_YELLOW,   // proceed with caution (night mode, main road)
    FLASHING_RED;      // stop, then go when clear (night mode side road, or fail-safe)

    /** Whether vehicles may enter the intersection without stopping. */
    public boolean permitsFlow() {
        return this == GREEN || this == YELLOW || this == FLASHING_YELLOW;
    }
}
