package com.lld.states.traffic.controller;

import com.lld.states.traffic.model.Approach;
import com.lld.states.traffic.model.LightColor;

import java.util.EnumMap;
import java.util.Map;

/**
 * The lamp driver. {@link #readBack()} reports what the lamps ACTUALLY show (in a real cabinet, measured
 * from the lamp circuits), which can differ from what was commanded if a relay sticks. The conflict
 * monitor trusts only the read-back.
 */
public interface SignalHardware {

    void drive(Map<Approach, LightColor> commanded);

    Map<Approach, LightColor> readBack();

    /** Healthy hardware: shows exactly what it is told. */
    static SignalHardware faithful() {
        return new SignalHardware() {
            private final Map<Approach, LightColor> lamps = new EnumMap<>(Approach.class);

            public void drive(Map<Approach, LightColor> commanded) {
                lamps.clear();
                lamps.putAll(commanded);
            }

            public Map<Approach, LightColor> readBack() {
                return new EnumMap<>(lamps);
            }
        };
    }
}
