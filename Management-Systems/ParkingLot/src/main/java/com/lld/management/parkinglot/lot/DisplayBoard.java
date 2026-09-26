package com.lld.management.parkinglot.lot;

import com.lld.management.parkinglot.model.SpotSize;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The "free spaces" sign at the entrance and on each floor. It never queries the lot: it is kept
 * up to date purely by events (Observer), like a real sign fed by the controller.
 */
public final class DisplayBoard implements ParkingEventListener {

    private final Map<Integer, Map<SpotSize, Integer>> free = new TreeMap<>();

    public DisplayBoard(ParkingLot lot) {
        for (ParkingFloor floor : lot.floors()) {
            Map<SpotSize, Integer> counts = new EnumMap<>(SpotSize.class);
            for (SpotSize s : SpotSize.values()) {
                counts.put(s, floor.freeCount(s));
            }
            free.put(floor.level(), counts);
        }
        lot.addListener(this);
    }

    @Override
    public synchronized void onAvailabilityChange(int floor, SpotSize size, int freeNow) {
        free.get(floor).put(size, freeNow);
    }

    public synchronized int free(int floor, SpotSize size) {
        return free.get(floor).get(size);
    }

    public synchronized String render() {
        StringBuilder sb = new StringBuilder();
        free.forEach((floor, counts) -> {
            sb.append("   Floor ").append(floor).append(": ");
            counts.forEach((size, n) -> sb.append(size).append('=').append(n == 0 ? "FULL" : n).append("  "));
            sb.append('\n');
        });
        return sb.toString();
    }
}
