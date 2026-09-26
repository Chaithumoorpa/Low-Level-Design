package com.lld.states.traffic.simulation;

import com.lld.states.traffic.controller.SignalHardware;
import com.lld.states.traffic.controller.TrafficController;
import com.lld.states.traffic.model.Approach;
import com.lld.states.traffic.model.LightColor;
import com.lld.states.traffic.model.TimingConfig;
import com.lld.states.traffic.timing.Detectors;
import com.lld.states.traffic.timing.TimingPlan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Cars arriving at random and leaving on green, one per second per approach (saturation flow).
 * The simulation is also the controller's {@link Detectors}: queue lengths are what the loops see.
 */
public final class IntersectionSimulation implements Detectors {

    private final Map<Approach, Double> arrivalRates;
    private final Map<Approach, Deque<Long>> queues = new EnumMap<>(Approach.class);
    private final Random random;
    private final TrafficController controller;
    private long vehiclesServed;
    private long totalDelay;
    private long maxDelay;
    private int maxQueue;

    /** @param arrivalRates probability that a car arrives on each approach in a given second */
    public IntersectionSimulation(TimingPlan plan, Map<Approach, Double> arrivalRates, long seed) {
        this(plan, TimingConfig.standard(), arrivalRates, seed, SignalHardware.faithful());
    }

    public IntersectionSimulation(TimingPlan plan, TimingConfig config, Map<Approach, Double> arrivalRates,
                                  long seed, SignalHardware hardware) {
        this.arrivalRates = new EnumMap<>(arrivalRates);
        this.random = new Random(seed);
        for (Approach a : Approach.values()) {
            queues.put(a, new ArrayDeque<>());
        }
        this.controller = new TrafficController(plan, config, this, hardware);
    }

    @Override
    public int queue(Approach approach) {
        return queues.get(approach).size();
    }

    public TrafficController controller() {
        return controller;
    }

    public void addVehicle(Approach approach) {
        queues.get(approach).add(controller.currentTick());
    }

    /** One second: arrivals, controller tick, departures. */
    public void step() {
        for (Approach a : Approach.values()) {
            if (random.nextDouble() < arrivalRates.getOrDefault(a, 0.0)) {
                addVehicle(a);
            }
        }
        controller.tick();
        long now = controller.currentTick();
        for (Approach a : Approach.values()) {
            LightColor lamp = controller.lampFor(a);
            boolean go = lamp == LightColor.GREEN || lamp == LightColor.FLASHING_YELLOW
                    || (lamp == LightColor.FLASHING_RED && now % 2 == 0);        // stop, then go
            Deque<Long> q = queues.get(a);
            if (go && !q.isEmpty()) {
                long delay = now - q.poll();
                vehiclesServed++;
                totalDelay += delay;
                maxDelay = Math.max(maxDelay, delay);
            }
            maxQueue = Math.max(maxQueue, q.size());
        }
    }

    public void run(int seconds) {
        for (int i = 0; i < seconds; i++) {
            step();
        }
    }

    public long vehiclesServed() {
        return vehiclesServed;
    }

    public double averageDelay() {
        return vehiclesServed == 0 ? 0 : (double) totalDelay / vehiclesServed;
    }

    public long maxDelay() {
        return maxDelay;
    }

    public int maxQueue() {
        return maxQueue;
    }

    @Override
    public String toString() {
        return String.format("served %d, avg delay %.1f s, max delay %d s, max queue %d",
                vehiclesServed, averageDelay(), maxDelay, maxQueue);
    }
}
