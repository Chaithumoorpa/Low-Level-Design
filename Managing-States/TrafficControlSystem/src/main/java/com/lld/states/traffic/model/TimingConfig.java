package com.lld.states.traffic.model;

/**
 * Safety timings in ticks (1 tick = 1 second). These do not depend on the timing plan: yellow and
 * all-red protect vehicles, walk + flashing-don't-walk protect pedestrians.
 */
public record TimingConfig(int yellow, int allRed, int walk, int flashingDontWalk) {

    public TimingConfig {
        if (yellow < 1 || allRed < 1 || walk < 1 || flashingDontWalk < 1) {
            throw new IllegalArgumentException("All safety intervals must be at least 1 tick");
        }
    }

    public static TimingConfig standard() {
        return new TimingConfig(3, 2, 7, 5);
    }

    public int pedestrianService() {
        return walk + flashingDontWalk;
    }
}
