package com.lld.states.traffic.controller;

import com.lld.states.traffic.model.Interval;
import com.lld.states.traffic.model.Phase;

/** Observer: logging, central traffic management, tests. */
public interface TrafficListener {

    default void onModeChange(ControllerStatus from, ControllerStatus to) {
    }

    default void onInterval(long tick, Phase phase, Interval interval) {
    }
}
