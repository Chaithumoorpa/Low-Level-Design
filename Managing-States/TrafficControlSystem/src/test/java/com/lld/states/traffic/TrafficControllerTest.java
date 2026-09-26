package com.lld.states.traffic;

import com.lld.states.traffic.controller.ConflictMonitor;
import com.lld.states.traffic.controller.ControllerStatus;
import com.lld.states.traffic.controller.SignalHardware;
import com.lld.states.traffic.controller.TrafficController;
import com.lld.states.traffic.controller.TrafficListener;
import com.lld.states.traffic.model.Approach;
import com.lld.states.traffic.model.Interval;
import com.lld.states.traffic.model.LightColor;
import com.lld.states.traffic.model.PedestrianSignal;
import com.lld.states.traffic.model.Phase;
import com.lld.states.traffic.model.TimingConfig;
import com.lld.states.traffic.simulation.IntersectionSimulation;
import com.lld.states.traffic.timing.ActuatedPlan;
import com.lld.states.traffic.timing.Detectors;
import com.lld.states.traffic.timing.FixedTimePlan;
import com.lld.states.traffic.timing.TimingPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TrafficControllerTest {

    private static final TimingConfig CONFIG = TimingConfig.standard();   // yellow 3, all-red 2, walk 7, flash 5

    private static TrafficController fixed(int ns, int ew) {
        return new TrafficController(new FixedTimePlan(ns, ew), CONFIG, Detectors.NONE, SignalHardware.faithful());
    }

    private static void run(TrafficController c, int ticks) {
        for (int i = 0; i < ticks; i++) {
            c.tick();
        }
    }

    /** Records every (phase, interval) the controller enters. */
    private static List<String> record(TrafficController c) {
        List<String> seen = new ArrayList<>();
        c.addListener(new TrafficListener() {
            @Override
            public void onInterval(long tick, Phase phase, Interval interval) {
                seen.add(tick + ":" + phase + ":" + interval);
            }
        });
        return seen;
    }

    // ------------------------------------------------------------------ normal cycle

    @Test
    void fixedCycleFollowsGreenYellowAllRed() {
        TrafficController c = fixed(10, 6);
        List<String> seen = record(c);

        run(c, 30);

        assertEquals(List.of(
                "10:NORTH_SOUTH:YELLOW", "13:NORTH_SOUTH:ALL_RED", "15:EAST_WEST:GREEN",
                "21:EAST_WEST:YELLOW", "24:EAST_WEST:ALL_RED", "26:NORTH_SOUTH:GREEN"), seen);
    }

    @Test
    void lampsMatchTheInterval() {
        TrafficController c = fixed(10, 6);
        assertEquals(LightColor.GREEN, c.lampFor(Approach.NORTH));
        assertEquals(LightColor.RED, c.lampFor(Approach.EAST));

        run(c, 10);                                       // NS yellow
        assertEquals(LightColor.YELLOW, c.lampFor(Approach.SOUTH));
        run(c, 3);                                        // all red
        for (Approach a : Approach.values()) {
            assertEquals(LightColor.RED, c.lampFor(a));
        }
        run(c, 2);                                        // EW green
        assertEquals(LightColor.GREEN, c.lampFor(Approach.WEST));
        assertEquals(LightColor.RED, c.lampFor(Approach.NORTH));
    }

    /** The one rule that must never break, checked on every tick of long random runs with random events. */
    @Test
    void crossingRoadsAreNeverBothAllowedToFlow() {
        for (long seed = 1; seed <= 5; seed++) {
            Random random = new Random(seed);
            TimingPlan plan = seed % 2 == 0 ? new ActuatedPlan(5, 40) : new FixedTimePlan(25, 20);
            IntersectionSimulation sim = new IntersectionSimulation(plan, Map.of(
                    Approach.NORTH, 0.3, Approach.SOUTH, 0.3, Approach.EAST, 0.2, Approach.WEST, 0.2), seed);
            TrafficController c = sim.controller();
            for (int t = 0; t < 5000; t++) {
                int event = random.nextInt(400);
                try {
                    if (event == 0) {
                        c.preempt(Approach.values()[random.nextInt(4)]);
                    } else if (event == 1) {
                        c.clearPreemption();
                    } else if (event == 2) {
                        c.enterNightMode();
                    } else if (event == 3) {
                        c.exitNightMode();
                    } else if (event < 20) {
                        c.requestPedestrian(Phase.values()[random.nextInt(2)]);
                    }
                } catch (IllegalStateException notInThisMode) {
                    // fine: the mode refused the command
                }
                sim.step();
                assertFalse(ConflictMonitor.hasConflict(c.lamps()), "seed " + seed + " tick " + t + ": " + c.lamps());
            }
            assertTrue(sim.vehiclesServed() > 0);
        }
    }

    @Test
    void greenNeverSwitchesWithoutYellowAndAllRed() {
        TrafficController c = new TrafficController(new ActuatedPlan(3, 20), CONFIG,
                a -> 1, SignalHardware.faithful());                  // demand everywhere
        List<String> seen = record(c);
        c.preempt(Approach.EAST);
        run(c, 20);
        c.clearPreemption();
        run(c, 200);

        for (int i = 1; i < seen.size(); i++) {
            String prev = seen.get(i - 1).split(":")[2];
            String cur = seen.get(i).split(":")[2];
            if (cur.equals("GREEN")) {
                assertEquals("ALL_RED", prev, "green must follow all-red: " + seen);
            }
            if (cur.equals("ALL_RED")) {
                assertEquals("YELLOW", prev, "all-red must follow yellow: " + seen);
            }
        }
    }

    // ------------------------------------------------------------------ pedestrians

    @Test
    void pedestrianGetsWalkThenFlashingDontWalkAndGreenIsExtended() {
        TrafficController c = fixed(10, 4);                  // EW green (4 s) shorter than ped service (12 s)
        c.requestPedestrian(Phase.EAST_WEST);
        run(c, 15);                                          // EW green starts at t=15
        assertEquals(Interval.GREEN, c.interval());
        assertEquals(Phase.EAST_WEST, c.phase());

        assertEquals(PedestrianSignal.WALK, c.pedestrianSignal(Phase.EAST_WEST));
        run(c, 7);
        assertEquals(PedestrianSignal.FLASHING_DONT_WALK, c.pedestrianSignal(Phase.EAST_WEST));
        run(c, 4);
        assertEquals(Interval.GREEN, c.interval(), "green held for the pedestrian beyond its 4 s");
        run(c, 1);
        assertEquals(Interval.YELLOW, c.interval());
        assertEquals(PedestrianSignal.DONT_WALK, c.pedestrianSignal(Phase.EAST_WEST));
        assertEquals(PedestrianSignal.DONT_WALK, c.pedestrianSignal(Phase.NORTH_SOUTH));
    }

    @Test
    void withoutARequestThereIsNoWalk() {
        TrafficController c = fixed(10, 10);
        run(c, 16);

        assertEquals(PedestrianSignal.DONT_WALK, c.pedestrianSignal(Phase.EAST_WEST));
    }

    // ------------------------------------------------------------------ actuated timing

    @Test
    void actuatedRestsInGreenWhenTheSideRoadIsEmpty() {
        Map<Approach, Integer> queues = new EnumMap<>(Approach.class);
        TrafficController c = new TrafficController(new ActuatedPlan(5, 30), CONFIG,
                a -> queues.getOrDefault(a, 0), SignalHardware.faithful());
        queues.put(Approach.NORTH, 3);

        run(c, 100);
        assertEquals(Phase.NORTH_SOUTH, c.phase());
        assertEquals(Interval.GREEN, c.interval(), "no demand on EW: keep NS green");

        queues.put(Approach.EAST, 1);
        run(c, 30);
        assertEquals(Phase.EAST_WEST, c.phase(), "max-out lets the side road through");
    }

    @Test
    void actuatedGapsOutWhenTheQueueEmpties() {
        Map<Approach, Integer> queues = new EnumMap<>(Approach.class);
        TrafficController c = new TrafficController(new ActuatedPlan(5, 60), CONFIG,
                a -> queues.getOrDefault(a, 0), SignalHardware.faithful());
        queues.put(Approach.EAST, 2);                        // side road waiting, main road empty

        run(c, 5);                                           // min green served
        assertEquals(Interval.YELLOW, c.interval());
    }

    @Test
    void actuatedBeatsFixedOnUnbalancedTraffic() {
        Map<Approach, Double> rates = Map.of(Approach.NORTH, 0.4, Approach.SOUTH, 0.4,
                Approach.EAST, 0.05, Approach.WEST, 0.05);
        IntersectionSimulation fixed = new IntersectionSimulation(new FixedTimePlan(30, 30), rates, 3);
        IntersectionSimulation actuated = new IntersectionSimulation(new ActuatedPlan(8, 60), rates, 3);
        fixed.run(3600);
        actuated.run(3600);

        assertTrue(actuated.averageDelay() < fixed.averageDelay() * 0.6,
                "actuated " + actuated.averageDelay() + " vs fixed " + fixed.averageDelay());
    }

    // ------------------------------------------------------------------ preemption

    @Test
    void preemptionClearsSafelyThenHoldsGreenForTheEmergencyRoad() {
        TrafficController c = fixed(30, 30);
        run(c, 5);                                           // NS green
        List<String> seen = record(c);

        c.preempt(Approach.EAST);
        assertEquals(ControllerStatus.PREEMPTION, c.status());
        run(c, 100);

        assertEquals(List.of("6:NORTH_SOUTH:YELLOW", "9:NORTH_SOUTH:ALL_RED", "11:EAST_WEST:GREEN"), seen);
        assertEquals(LightColor.GREEN, c.lampFor(Approach.EAST), "held as long as needed");

        c.clearPreemption();
        run(c, 1);
        assertEquals(Interval.YELLOW, c.interval(), "fixed plan resumes: EW green already over its time");
    }

    @Test
    void preemptionOnTheRoadAlreadyGreenJustHoldsIt() {
        TrafficController c = fixed(10, 10);
        run(c, 5);
        c.preempt(Approach.SOUTH);
        run(c, 50);

        assertEquals(Phase.NORTH_SOUTH, c.phase());
        assertEquals(Interval.GREEN, c.interval());
    }

    @Test
    void pedestrianRequestDuringPreemptionIsServedAfterwards() {
        TrafficController c = fixed(10, 10);
        c.preempt(Approach.NORTH);
        c.requestPedestrian(Phase.EAST_WEST);
        run(c, 20);
        assertEquals(PedestrianSignal.DONT_WALK, c.pedestrianSignal(Phase.EAST_WEST));

        c.clearPreemption();
        run(c, 6);                                           // NS yellow(3) + all-red(2) + 1
        assertEquals(Phase.EAST_WEST, c.phase());
        assertEquals(PedestrianSignal.WALK, c.pedestrianSignal(Phase.EAST_WEST));
    }

    // ------------------------------------------------------------------ night mode and fail-safe

    @Test
    void nightModeFlashesAfterSafeClearanceAndRestartsWithAllRed() {
        TrafficController c = fixed(20, 20);
        run(c, 3);
        c.enterNightMode();
        run(c, 1);
        assertEquals(LightColor.YELLOW, c.lampFor(Approach.NORTH), "never straight from green to flash");
        run(c, 10);

        assertEquals(LightColor.FLASHING_YELLOW, c.lampFor(Approach.NORTH));
        assertEquals(LightColor.FLASHING_RED, c.lampFor(Approach.EAST));
        assertThrows(IllegalStateException.class, () -> c.preempt(Approach.EAST));

        c.exitNightMode();
        assertEquals(Interval.ALL_RED, c.interval());
        run(c, 2);
        assertEquals(Phase.NORTH_SOUTH, c.phase());
        assertEquals(LightColor.GREEN, c.lampFor(Approach.NORTH));
    }

    @Test
    void stuckLampTriggersFailSafeFlashingRed() {
        boolean[] relayStuck = {false};
        SignalHardware faulty = new SignalHardware() {
            private final SignalHardware inner = SignalHardware.faithful();

            public void drive(Map<Approach, LightColor> commanded) {
                inner.drive(commanded);
            }

            public Map<Approach, LightColor> readBack() {
                Map<Approach, LightColor> actual = inner.readBack();
                if (relayStuck[0]) {
                    actual.put(Approach.EAST, LightColor.GREEN);       // relay welded shut
                }
                return actual;
            }
        };
        List<ControllerStatus> modes = new ArrayList<>();
        TrafficController c = new TrafficController(new FixedTimePlan(10, 10), CONFIG, Detectors.NONE, faulty);
        c.addListener(new TrafficListener() {
            @Override
            public void onModeChange(ControllerStatus from, ControllerStatus to) {
                modes.add(to);
            }
        });

        run(c, 3);
        assertEquals(ControllerStatus.NORMAL, c.status());
        relayStuck[0] = true;
        run(c, 1);                                           // NS green + stuck EW green = conflict

        assertEquals(ControllerStatus.FAIL_SAFE, c.status());
        for (Approach a : Approach.values()) {
            assertEquals(LightColor.FLASHING_RED, c.lampFor(a));
        }
        assertEquals(List.of(ControllerStatus.FAIL_SAFE), modes);
        assertThrows(IllegalStateException.class, c::enterNightMode);

        relayStuck[0] = false;                               // technician repairs the cabinet
        c.resetFailSafe();
        run(c, 2);
        assertEquals(ControllerStatus.NORMAL, c.status());
        assertEquals(LightColor.GREEN, c.lampFor(Approach.NORTH));
    }

    @Test
    void conflictMonitorRules() {
        Map<Approach, LightColor> ok = new EnumMap<>(Approach.class);
        ok.put(Approach.NORTH, LightColor.GREEN);
        ok.put(Approach.SOUTH, LightColor.GREEN);
        ok.put(Approach.EAST, LightColor.RED);
        ok.put(Approach.WEST, LightColor.FLASHING_RED);
        assertFalse(ConflictMonitor.hasConflict(ok));

        ok.put(Approach.WEST, LightColor.YELLOW);
        assertTrue(ConflictMonitor.hasConflict(ok), "yellow still lets cars in");
    }

    @Test
    void invalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new FixedTimePlan(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ActuatedPlan(10, 5));
        assertThrows(IllegalArgumentException.class, () -> new TimingConfig(0, 2, 7, 5));
        TrafficController c = fixed(10, 10);
        assertThrows(IllegalStateException.class, c::clearPreemption);
        assertThrows(IllegalStateException.class, c::exitNightMode);
        assertThrows(IllegalStateException.class, c::resetFailSafe);
    }
}
