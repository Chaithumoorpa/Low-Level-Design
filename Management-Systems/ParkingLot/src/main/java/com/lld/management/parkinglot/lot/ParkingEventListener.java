package com.lld.management.parkinglot.lot;

import com.lld.management.parkinglot.model.Receipt;
import com.lld.management.parkinglot.model.SpotSize;
import com.lld.management.parkinglot.model.Ticket;
import com.lld.management.parkinglot.model.VehicleType;

/** Observer: display boards, occupancy analytics, billing. */
public interface ParkingEventListener {

    default void onEntry(Ticket ticket) {
    }

    default void onExit(Receipt receipt) {
    }

    /** Free spots of a size on a floor changed. */
    default void onAvailabilityChange(int floor, SpotSize size, int freeNow) {
    }

    default void onFull(VehicleType turnedAway) {
    }
}
