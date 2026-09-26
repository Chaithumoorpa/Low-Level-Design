package com.lld.states.traffic.controller;

import com.lld.states.traffic.model.Approach;
import com.lld.states.traffic.model.Interval;
import com.lld.states.traffic.model.LightColor;
import com.lld.states.traffic.model.PedestrianSignal;
import com.lld.states.traffic.model.Phase;
import com.lld.states.traffic.model.TimingConfig;
import com.lld.states.traffic.timing.Detectors;
import com.lld.states.traffic.timing.TimingPlan;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Controller for a four-way intersection: the <b>context</b> of the State pattern.
 *
 * <p>Two levels of state:
 * <ul>
 *   <li><b>Mode</b> (State pattern): NORMAL, PREEMPTION, NIGHT_FLASH, FAIL_SAFE.</li>
 *   <li><b>Interval</b> inside NORMAL and PREEMPTION: the current phase is GREEN → YELLOW → ALL_RED.
 *       Every change of phase passes through yellow and all-red; nothing can skip them.</li>
 * </ul>
 * After driving the lamps each tick, the controller reads them back and runs the {@link ConflictMonitor};
 * a conflict forces FAIL_SAFE immediately.
 */
public final class TrafficController {

    private final TimingPlan plan;
    private final TimingConfig config;
    private final Detectors detectors;
    private final SignalHardware hardware;
    private final List<TrafficListener> listeners = new ArrayList<>();
    private final Set<Phase> pedestrianRequests = EnumSet.noneOf(Phase.class);

    private ControllerMode mode = Modes.NORMAL;
    private Phase phase = Phase.NORTH_SOUTH;
    private Interval interval = Interval.GREEN;
    private int elapsed;
    private boolean servingPedestrians;
    private Phase preemptTarget;
    private boolean flashing;
    private long tick;

    public TrafficController(TimingPlan plan, TimingConfig config, Detectors detectors, SignalHardware hardware) {
        this.plan = Objects.requireNonNull(plan);
        this.config = Objects.requireNonNull(config);
        this.detectors = Objects.requireNonNull(detectors);
        this.hardware = Objects.requireNonNull(hardware);
        hardware.drive(commandedLamps());
    }

    public void addListener(TrafficListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ commands → current mode

    /** Advances one second: mode logic, drive lamps, read back, conflict check. */
    public void tick() {
        tick++;
        mode.tick(this);
        hardware.drive(commandedLamps());
        if (mode != Modes.FAIL_SAFE && ConflictMonitor.hasConflict(hardware.readBack())) {
            switchMode(Modes.FAIL_SAFE);
            hardware.drive(commandedLamps());
        }
    }

    /** Push button for the crossings that walk alongside {@code phase}. */
    public void requestPedestrian(Phase phase) {
        mode.requestPedestrian(this, phase);
    }

    public void preempt(Approach approach) {
        mode.preempt(this, approach);
    }

    public void clearPreemption() {
        mode.clearPreemption(this);
    }

    public void enterNightMode() {
        mode.enterNightMode(this);
    }

    public void exitNightMode() {
        mode.exitNightMode(this);
    }

    public void resetFailSafe() {
        mode.resetFailSafe(this);
    }

    // ------------------------------------------------------------------ what is displayed

    public LightColor lampFor(Approach approach) {
        return commandedLamps().get(approach);
    }

    public Map<Approach, LightColor> lamps() {
        return commandedLamps();
    }

    public PedestrianSignal pedestrianSignal(Phase crossing) {
        if (mode != Modes.NORMAL || crossing != phase || interval != Interval.GREEN || !servingPedestrians) {
            return PedestrianSignal.DONT_WALK;
        }
        if (elapsed < config.walk()) {
            return PedestrianSignal.WALK;
        }
        return elapsed < config.pedestrianService() ? PedestrianSignal.FLASHING_DONT_WALK : PedestrianSignal.DONT_WALK;
    }

    public ControllerStatus status() {
        return mode.status();
    }

    public Phase phase() {
        return phase;
    }

    public Interval interval() {
        return interval;
    }

    public long currentTick() {
        return tick;
    }

    private Map<Approach, LightColor> commandedLamps() {
        Map<Approach, LightColor> lamps = new EnumMap<>(Approach.class);
        for (Approach a : Approach.values()) {
            lamps.put(a, colorFor(a));
        }
        return lamps;
    }

    private LightColor colorFor(Approach a) {
        if (mode == Modes.FAIL_SAFE) {
            return LightColor.FLASHING_RED;
        }
        if (mode == Modes.NIGHT_FLASH && flashing) {
            return Phase.NORTH_SOUTH.includes(a) ? LightColor.FLASHING_YELLOW : LightColor.FLASHING_RED;
        }
        if (!phase.includes(a)) {
            return LightColor.RED;
        }
        return switch (interval) {
            case GREEN -> LightColor.GREEN;
            case YELLOW -> LightColor.YELLOW;
            case ALL_RED -> LightColor.RED;
        };
    }

    // ------------------------------------------------------------------ mode steps (called by Modes)

    void normalStep() {
        elapsed++;
        switch (interval) {
            case GREEN -> {
                if (servingPedestrians && elapsed < config.pedestrianService()) {
                    return;                              // never cut a pedestrian crossing short
                }
                Phase other = phase.next();
                boolean otherDemand = detectors.queue(other) > 0 || pedestrianRequests.contains(other);
                if (plan.shouldEndGreen(phase, elapsed, detectors, otherDemand)) {
                    setInterval(Interval.YELLOW);
                }
            }
            case YELLOW -> {
                if (elapsed >= config.yellow()) {
                    setInterval(Interval.ALL_RED);
                }
            }
            case ALL_RED -> {
                if (elapsed >= config.allRed()) {
                    startGreen(phase.next());
                }
            }
        }
    }

    void preemptionStep() {
        if (phase == preemptTarget && interval == Interval.GREEN) {
            elapsed++;                                   // hold green for the emergency vehicle
            return;
        }
        clearanceStep(() -> startGreen(preemptTarget));
    }

    void nightStep() {
        if (!flashing) {
            clearanceStep(() -> flashing = true);
        }
    }

    void queuePedestrian(Phase crossing) {
        pedestrianRequests.add(crossing);
    }

    void beginPreemption(Phase target) {
        preemptTarget = target;
        servingPedestrians = false;                      // pedestrians see DON'T WALK at once
        switchMode(Modes.PREEMPTION);
    }

    void endPreemption() {
        preemptTarget = null;
        switchMode(Modes.NORMAL);                        // normal timing continues from here
    }

    void beginNightMode() {
        flashing = false;
        switchMode(Modes.NIGHT_FLASH);
    }

    void endNightMode() {
        flashing = false;
        restartFromAllRed();
    }

    /** Leaving flash (night or fail-safe): all-red first, then the main road gets green. */
    void restartFromAllRed() {
        phase = Phase.EAST_WEST;                         // so the next green is NORTH_SOUTH
        servingPedestrians = false;
        switchMode(Modes.NORMAL);
        setInterval(Interval.ALL_RED);
    }

    // ------------------------------------------------------------------ internals

    /** Green → yellow → all-red, then run {@code whenClear}. Used by preemption and night mode. */
    private void clearanceStep(Runnable whenClear) {
        elapsed++;
        switch (interval) {
            case GREEN -> setInterval(Interval.YELLOW);
            case YELLOW -> {
                if (elapsed >= config.yellow()) {
                    setInterval(Interval.ALL_RED);
                }
            }
            case ALL_RED -> {
                if (elapsed >= config.allRed()) {
                    whenClear.run();
                }
            }
        }
    }

    private void startGreen(Phase next) {
        phase = next;
        servingPedestrians = mode == Modes.NORMAL && pedestrianRequests.remove(next);
        setInterval(Interval.GREEN);
    }

    private void setInterval(Interval next) {
        interval = next;
        elapsed = 0;
        listeners.forEach(l -> l.onInterval(tick, phase, next));
    }

    private void switchMode(ControllerMode next) {
        ControllerStatus from = mode.status();
        mode = next;
        listeners.forEach(l -> l.onModeChange(from, next.status()));
    }
}
