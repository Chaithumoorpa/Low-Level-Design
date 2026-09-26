package com.lld.management.parkinglot.model;

import java.time.Instant;

/** Issued at the entry gate; presented at the exit to pay. */
public record Ticket(String id, Vehicle vehicle, ParkingSpot spot, String entryGate, Instant entryTime) {

    @Override
    public String toString() {
        return id + " " + vehicle.licensePlate() + " @ " + spot.id() + " since " + entryTime;
    }
}
