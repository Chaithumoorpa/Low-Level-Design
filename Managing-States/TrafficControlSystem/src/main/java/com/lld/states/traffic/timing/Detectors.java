package com.lld.states.traffic.timing;

import com.lld.states.traffic.model.Approach;
import com.lld.states.traffic.model.Phase;

/** What the in-road loop detectors report: vehicles queued on each approach. */
@FunctionalInterface
public interface Detectors {

    int queue(Approach approach);

    default int queue(Phase phase) {
        int sum = 0;
        for (Approach a : phase.approaches()) {
            sum += queue(a);
        }
        return sum;
    }

    Detectors NONE = approach -> 0;
}
