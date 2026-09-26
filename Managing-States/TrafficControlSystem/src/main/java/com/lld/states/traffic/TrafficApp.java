package com.lld.states.traffic;

import com.lld.states.traffic.controller.ControllerStatus;
import com.lld.states.traffic.controller.TrafficController;
import com.lld.states.traffic.controller.TrafficListener;
import com.lld.states.traffic.model.Approach;
import com.lld.states.traffic.model.Interval;
import com.lld.states.traffic.model.Phase;
import com.lld.states.traffic.simulation.IntersectionSimulation;
import com.lld.states.traffic.timing.ActuatedPlan;
import com.lld.states.traffic.timing.FixedTimePlan;

import java.util.Map;

/** Demo: a traced cycle with a pedestrian and an ambulance, then fixed vs actuated timing. */
public class TrafficApp {

    public static void main(String[] args) {
        tracedScenario();
        compareTimingPlans();
    }

    private static void tracedScenario() {
        System.out.println("1) Fixed plan (NS 20 s, EW 15 s), yellow 3 s, all-red 2 s");
        IntersectionSimulation sim = new IntersectionSimulation(new FixedTimePlan(20, 15),
                Map.of(Approach.NORTH, 0.2, Approach.SOUTH, 0.2, Approach.EAST, 0.2, Approach.WEST, 0.2), 1);
        TrafficController c = sim.controller();
        c.addListener(new TrafficListener() {
            @Override
            public void onInterval(long tick, Phase phase, Interval interval) {
                System.out.printf("   t=%-4d %-11s %-7s lamps=%s%n", tick, phase, interval, c.lamps());
            }

            @Override
            public void onModeChange(ControllerStatus from, ControllerStatus to) {
                System.out.printf("   t=%-4d MODE %s -> %s%n", c.currentTick(), from, to);
            }
        });

        sim.run(10);
        System.out.println("   t=10   pedestrian presses the button to cross alongside EAST_WEST");
        c.requestPedestrian(Phase.EAST_WEST);
        sim.run(17);
        System.out.println("   t=27   EW pedestrian signal: " + c.pedestrianSignal(Phase.EAST_WEST));
        sim.run(8);
        System.out.println("   t=35   EW pedestrian signal: " + c.pedestrianSignal(Phase.EAST_WEST));
        sim.run(10);
        System.out.println("   t=45   ambulance approaching from the EAST");
        c.preempt(Approach.EAST);
        sim.run(10);
        System.out.println("   t=55   ambulance has passed");
        c.clearPreemption();
        sim.run(15);
    }

    private static void compareTimingPlans() {
        System.out.println();
        System.out.println("2) Busy main road (NS 0.4 cars/s each way), quiet side road (EW 0.05), 1 hour");
        Map<Approach, Double> rates = Map.of(Approach.NORTH, 0.4, Approach.SOUTH, 0.4,
                Approach.EAST, 0.05, Approach.WEST, 0.05);
        IntersectionSimulation fixed = new IntersectionSimulation(new FixedTimePlan(30, 30), rates, 99);
        IntersectionSimulation actuated = new IntersectionSimulation(new ActuatedPlan(8, 60), rates, 99);
        fixed.run(3600);
        actuated.run(3600);
        System.out.println("   fixed 30/30   : " + fixed);
        System.out.println("   actuated 8-60 : " + actuated);
    }
}
