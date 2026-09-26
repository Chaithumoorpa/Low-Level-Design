package com.lld.management.parkinglot.model;

/** Refusals shown on the gate display: lot full, unknown ticket, payment declined, ... */
public class ParkingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ParkingException(String message) {
        super(message);
    }
}
