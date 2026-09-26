package com.lld.states.traffic.timing;

import com.lld.states.traffic.model.Phase;

/**
 * Traffic-actuated plan, driven by the detectors:
 * <ul>
 *   <li><b>rest in green</b>: if nobody waits on the other road, keep the green (no pointless cycling);</li>
 *   <li><b>min green</b>: once started, a green lasts at least {@code minGreen};</li>
 *   <li><b>gap out</b>: after min green, end as soon as this phase's queue is empty;</li>
 *   <li><b>max out</b>: never exceed {@code maxGreen} while others wait, so the side road can't starve.</li>
 * </ul>
 */
public class ActuatedPlan implements TimingPlan {

    private final int minGreen;
    private final int maxGreen;

    public ActuatedPlan(int minGreen, int maxGreen) {
        if (minGreen < 1 || maxGreen < minGreen) {
            throw new IllegalArgumentException("Need 1 <= minGreen <= maxGreen");
        }
        this.minGreen = minGreen;
        this.maxGreen = maxGreen;
    }

    @Override
    public boolean shouldEndGreen(Phase phase, int greenElapsed, Detectors detectors, boolean otherPhaseDemand) {
        if (!otherPhaseDemand) {
            return false;                                // rest in green
        }
        if (greenElapsed < minGreen) {
            return false;
        }
        return detectors.queue(phase) == 0 || greenElapsed >= maxGreen;   // gap out or max out
    }
}
