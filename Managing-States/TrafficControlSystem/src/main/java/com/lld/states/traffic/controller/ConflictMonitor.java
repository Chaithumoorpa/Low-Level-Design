package com.lld.states.traffic.controller;

import com.lld.states.traffic.model.Approach;
import com.lld.states.traffic.model.LightColor;
import com.lld.states.traffic.model.Phase;

import java.util.Map;

/**
 * Independent safety check (the "conflict monitor" / "malfunction management unit" of real
 * controllers): if lamps on crossing roads both let traffic flow, the intersection is dangerous and
 * must go to all-way flashing red immediately, whatever the controller logic believes.
 */
public final class ConflictMonitor {

    private ConflictMonitor() {
    }

    public static boolean hasConflict(Map<Approach, LightColor> lamps) {
        return flows(lamps, Phase.NORTH_SOUTH) && flows(lamps, Phase.EAST_WEST);
    }

    private static boolean flows(Map<Approach, LightColor> lamps, Phase phase) {
        for (Approach a : phase.approaches()) {
            LightColor c = lamps.get(a);
            if (c != null && c.permitsFlow()) {
                return true;
            }
        }
        return false;
    }
}
