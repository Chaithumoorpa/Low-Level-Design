package com.lld.states.traffic.timing;

import com.lld.states.traffic.model.Phase;

/**
 * Strategy: how long each green lasts. The controller asks once per tick of green whether it may
 * end now. Yellow, all-red and pedestrian minimums are enforced by the controller regardless.
 *
 * @see FixedTimePlan
 * @see ActuatedPlan
 */
public interface TimingPlan {

    /**
     * @param greenElapsed    ticks the current green has lasted
     * @param otherPhaseDemand whether anyone (vehicle or pedestrian) is waiting for the other phase
     */
    boolean shouldEndGreen(Phase phase, int greenElapsed, Detectors detectors, boolean otherPhaseDemand);
}
