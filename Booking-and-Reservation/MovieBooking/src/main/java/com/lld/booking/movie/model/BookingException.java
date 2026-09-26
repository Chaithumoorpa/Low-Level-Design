package com.lld.booking.movie.model;

/** Seat taken, hold expired, show overlap, too late to cancel... */
public class BookingException extends RuntimeException {

    public BookingException(String message) {
        super(message);
    }
}
