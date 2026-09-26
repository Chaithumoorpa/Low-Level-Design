package com.lld.management.parkinglot.model;

import java.util.Locale;

/** A vehicle, identified by its (normalised) licence plate. */
public record Vehicle(String licensePlate, VehicleType type) {

    public Vehicle {
        if (licensePlate == null || licensePlate.isBlank()) {
            throw new IllegalArgumentException("Licence plate is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("Vehicle type is required");
        }
        licensePlate = normalizePlate(licensePlate);
    }

    /** "ka 01-ab 1234" and "KA01AB1234" are the same plate. */
    public static String normalizePlate(String plate) {
        return plate.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static Vehicle of(String plate, VehicleType type) {
        return new Vehicle(plate, type);
    }
}
