package com.lld.management.parkinglot.allocation;

import com.lld.management.parkinglot.lot.ParkingFloor;
import com.lld.management.parkinglot.model.ParkingSpot;
import com.lld.management.parkinglot.model.SpotSize;
import com.lld.management.parkinglot.model.VehicleType;

import java.util.List;
import java.util.Optional;

/**
 * Smallest suitable spot first (anywhere in the building), then the nearest of that size.
 * Keeps large spots for vans and trucks, and EV spots for electric cars.
 */
public class BestFitStrategy implements SpotAllocationStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(VehicleType type, List<ParkingFloor> floors) {
        for (SpotSize size : type.allowedSpots()) {                  // tightest fit first
            for (ParkingFloor floor : floors) {                      // lowest floor first
                Optional<ParkingSpot> spot = floor.nearestFree(size);
                if (spot.isPresent()) {
                    return spot;
                }
            }
        }
        return Optional.empty();
    }
}
