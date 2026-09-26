package com.lld.management.parkinglot.allocation;

import com.lld.management.parkinglot.lot.ParkingFloor;
import com.lld.management.parkinglot.model.ParkingSpot;
import com.lld.management.parkinglot.model.SpotSize;
import com.lld.management.parkinglot.model.VehicleType;

import java.util.List;
import java.util.Optional;

/**
 * Closest suitable spot to the entrance: lowest floor, then lowest number, whatever its size.
 * Convenient for drivers, but a car may take a large spot a truck will need later (see the tests).
 */
public class NearestSpotStrategy implements SpotAllocationStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(VehicleType type, List<ParkingFloor> floors) {
        for (ParkingFloor floor : floors) {
            ParkingSpot best = null;
            for (SpotSize size : type.allowedSpots()) {
                ParkingSpot candidate = floor.nearestFree(size).orElse(null);
                if (candidate != null && (best == null || candidate.compareTo(best) < 0)) {
                    best = candidate;
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }
}
