package com.lld.states.traffic.model;

/** Pedestrian head for the crossings that walk alongside a phase. */
public enum PedestrianSignal {
    WALK,
    FLASHING_DONT_WALK,   // finish crossing, don't start
    DONT_WALK
}
