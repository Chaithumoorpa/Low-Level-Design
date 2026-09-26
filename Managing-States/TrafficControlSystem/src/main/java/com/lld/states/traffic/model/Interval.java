package com.lld.states.traffic.model;

/**
 * The three intervals every phase goes through in normal operation. Green can never jump straight to
 * the other phase: it must pass yellow (warning) and all-red (clearing the box).
 */
public enum Interval {
    GREEN,
    YELLOW,
    ALL_RED
}
