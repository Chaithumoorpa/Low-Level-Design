package com.lld.booking.movie.model;

/** A physical seat, e.g. "C7" = row C, number 7. Seats are numbered left to right without gaps. */
public record Seat(String id, char row, int number, SeatType type) {

    public static String id(char row, int number) {
        return String.valueOf(row) + number;
    }
}
