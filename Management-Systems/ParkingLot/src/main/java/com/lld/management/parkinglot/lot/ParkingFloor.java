package com.lld.management.parkinglot.lot;

import com.lld.management.parkinglot.model.ParkingSpot;
import com.lld.management.parkinglot.model.SpotSize;
import com.lld.management.parkinglot.model.Vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;

/**
 * One level of the garage. Free spots are indexed per size in a sorted set (nearest first), so
 * "nearest free compact spot on this floor" is O(log n) instead of scanning every bay.
 *
 * <p>Not thread-safe on its own: the {@link ParkingLot} calls it while holding its allocation lock.
 */
public final class ParkingFloor {

    private final int level;
    private final List<ParkingSpot> spots = new ArrayList<>();
    private final Map<SpotSize, NavigableSet<ParkingSpot>> free = new EnumMap<>(SpotSize.class);

    /** @param layout how many spots of each size, numbered in the order given (lower = nearer) */
    public ParkingFloor(int level, Map<SpotSize, Integer> layout) {
        this.level = level;
        for (SpotSize s : SpotSize.values()) {
            free.put(s, new TreeSet<>());
        }
        int number = 1;
        for (Map.Entry<SpotSize, Integer> e : new LinkedHashMap<>(layout).entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                ParkingSpot spot = new ParkingSpot(level, number++, e.getKey());
                spots.add(spot);
                free.get(e.getKey()).add(spot);
            }
        }
    }

    public int level() {
        return level;
    }

    public Optional<ParkingSpot> nearestFree(SpotSize size) {
        NavigableSet<ParkingSpot> set = free.get(size);
        return set.isEmpty() ? Optional.empty() : Optional.of(set.first());
    }

    void occupy(ParkingSpot spot, Vehicle vehicle) {
        spot.occupy(vehicle);
        free.get(spot.size()).remove(spot);
    }

    void release(ParkingSpot spot) {
        spot.release();
        free.get(spot.size()).add(spot);
    }

    void setOutOfService(ParkingSpot spot, boolean outOfService) {
        spot.setOutOfService(outOfService);
        if (spot.isFree()) {
            free.get(spot.size()).add(spot);
        } else {
            free.get(spot.size()).remove(spot);
        }
    }

    public int freeCount(SpotSize size) {
        return free.get(size).size();
    }

    public int capacity(SpotSize size) {
        return (int) spots.stream().filter(s -> s.size() == size).count();
    }

    public List<ParkingSpot> spots() {
        return Collections.unmodifiableList(spots);
    }

    public Optional<ParkingSpot> spot(String id) {
        return spots.stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
