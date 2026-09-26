package com.lld.booking.ride.model;

/** Quote expired, rider already on a trip, wrong driver, too late to cancel... */
public class RideException extends RuntimeException {

    public RideException(String message) {
        super(message);
    }
}
