package com.lld.states.traffic.controller;

import com.lld.states.traffic.model.Approach;
import com.lld.states.traffic.model.Phase;

/** The four operating modes. Data (phase, timers, requests) lives in {@link TrafficController}. */
final class Modes {

    private Modes() {
    }

    /** Normal cycling: green → yellow → all-red → other phase, timed by the plan. */
    static final ControllerMode NORMAL = new ControllerMode() {
        public ControllerStatus status() {
            return ControllerStatus.NORMAL;
        }

        public void tick(TrafficController c) {
            c.normalStep();
        }

        public void requestPedestrian(TrafficController c, Phase phase) {
            c.queuePedestrian(phase);
        }

        public void preempt(TrafficController c, Approach approach) {
            c.beginPreemption(Phase.of(approach));
        }

        public void enterNightMode(TrafficController c) {
            c.beginNightMode();
        }
    };

    /** Emergency vehicle: clear the intersection safely, then give its road green and hold it. */
    static final ControllerMode PREEMPTION = new ControllerMode() {
        public ControllerStatus status() {
            return ControllerStatus.PREEMPTION;
        }

        public void tick(TrafficController c) {
            c.preemptionStep();
        }

        public void requestPedestrian(TrafficController c, Phase phase) {
            c.queuePedestrian(phase);                   // remembered, served after the emergency
        }

        public void preempt(TrafficController c, Approach approach) {
            c.beginPreemption(Phase.of(approach));      // a second vehicle from another road
        }

        public void clearPreemption(TrafficController c) {
            c.endPreemption();
        }
    };

    /** Low traffic at night: flashing yellow on the main road, flashing red on the side road. */
    static final ControllerMode NIGHT_FLASH = new ControllerMode() {
        public ControllerStatus status() {
            return ControllerStatus.NIGHT_FLASH;
        }

        public void tick(TrafficController c) {
            c.nightStep();
        }

        public void exitNightMode(TrafficController c) {
            c.endNightMode();
        }
    };

    /** A conflict was seen on the lamps: all-way flashing red until a technician resets it. */
    static final ControllerMode FAIL_SAFE = new ControllerMode() {
        public ControllerStatus status() {
            return ControllerStatus.FAIL_SAFE;
        }

        public void tick(TrafficController c) {
            // hold flashing red
        }

        public void resetFailSafe(TrafficController c) {
            c.restartFromAllRed();
        }
    };
}
