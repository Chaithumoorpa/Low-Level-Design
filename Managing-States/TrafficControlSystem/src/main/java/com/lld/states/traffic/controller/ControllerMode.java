package com.lld.states.traffic.controller;

import com.lld.states.traffic.model.Approach;

/**
 * State pattern for the controller's operating mode. Each tick goes to the current mode; operator and
 * emergency commands are allowed only in the modes that override them.
 */
interface ControllerMode {

    ControllerStatus status();

    void tick(TrafficController c);

    default void requestPedestrian(TrafficController c, com.lld.states.traffic.model.Phase phase) {
        throw notHere("accept pedestrian requests");
    }

    default void preempt(TrafficController c, Approach approach) {
        throw notHere("start emergency preemption");
    }

    default void clearPreemption(TrafficController c) {
        throw notHere("clear preemption");
    }

    default void enterNightMode(TrafficController c) {
        throw notHere("enter night mode");
    }

    default void exitNightMode(TrafficController c) {
        throw notHere("exit night mode");
    }

    default void resetFailSafe(TrafficController c) {
        throw notHere("reset fail-safe");
    }

    private IllegalStateException notHere(String action) {
        return new IllegalStateException("Cannot " + action + " in " + status() + " mode");
    }
}
