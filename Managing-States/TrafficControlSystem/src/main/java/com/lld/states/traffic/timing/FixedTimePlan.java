package com.lld.states.traffic.timing;

import com.lld.states.traffic.model.Phase;

/**
 * Pre-timed plan: each phase gets a fixed green, whatever the traffic. Predictable, and good for
 * coordinating a row of signals, but wasteful when one road is empty.
 */
public class FixedTimePlan implements TimingPlan {

    private final int northSouthGreen;
    private final int eastWestGreen;

    public FixedTimePlan(int northSouthGreen, int eastWestGreen) {
        if (northSouthGreen < 1 || eastWestGreen < 1) {
            throw new IllegalArgumentException("Green times must be positive");
        }
        this.northSouthGreen = northSouthGreen;
        this.eastWestGreen = eastWestGreen;
    }

    @Override
    public boolean shouldEndGreen(Phase phase, int greenElapsed, Detectors detectors, boolean otherPhaseDemand) {
        return greenElapsed >= (phase == Phase.NORTH_SOUTH ? northSouthGreen : eastWestGreen);
    }
}
