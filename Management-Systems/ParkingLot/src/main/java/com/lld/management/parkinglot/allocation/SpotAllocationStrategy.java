package com.lld.management.parkinglot.allocation;

import com.lld.management.parkinglot.lot.ParkingFloor;
import com.lld.management.parkinglot.model.ParkingSpot;
import com.lld.management.parkinglot.model.VehicleType;

import java.util.List;
import java.util.Optional;

/**
 * Strategy: which free spot a vehicle gets. Floors are given lowest first. The lot calls this while
 * holding its allocation lock, so implementations need no locking of their own.
 */
public interface SpotAllocationStrategy {

    Optional<ParkingSpot> findSpot(VehicleType type, List<ParkingFloor> floors);
}
