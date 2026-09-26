package com.lld.booking.movie.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * One screening of a movie on a screen. Owns the state of every seat for this screening. All seat
 * changes happen while holding this object's monitor (the booking service synchronises on it), so
 * two customers can never get the same seat.
 */
public final class Show {

    public enum SeatStatus {
        AVAILABLE, HELD, BOOKED
    }

    private final String id;
    private final Movie movie;
    private final Screen screen;
    private final Instant startsAt;
    private final Instant endsAt;
    private final Map<String, SeatStatus> status = new HashMap<>();
    private final Map<String, String> owner = new HashMap<>();        // seat -> hold id or booking id

    public Show(String id, Movie movie, Screen screen, Instant startsAt) {
        this.id = id;
        this.movie = movie;
        this.screen = screen;
        this.startsAt = startsAt;
        this.endsAt = startsAt.plusSeconds(movie.minutes() * 60L);
        screen.seats().forEach(s -> status.put(s.id(), SeatStatus.AVAILABLE));
    }

    public String id() {
        return id;
    }

    public Movie movie() {
        return movie;
    }

    public Screen screen() {
        return screen;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public SeatStatus status(String seatId) {
        return status.get(seatId);
    }

    public String owner(String seatId) {
        return owner.get(seatId);
    }

    public void set(String seatId, SeatStatus s, String ownerId) {
        status.put(seatId, s);
        if (s == SeatStatus.AVAILABLE) {
            owner.remove(seatId);
        } else {
            owner.put(seatId, ownerId);
        }
    }

    public long count(SeatStatus s) {
        return status.values().stream().filter(x -> x == s).count();
    }

    /** A text seat map: '.' free, 'h' held, 'X' booked. */
    public String seatMap() {
        StringBuilder sb = new StringBuilder();
        screen.rows().forEach((row, seats) -> {
            sb.append("      ").append(row).append(' ');
            for (Seat s : seats) {
                sb.append(switch (status.get(s.id())) {
                    case AVAILABLE -> '.';
                    case HELD -> 'h';
                    case BOOKED -> 'X';
                });
            }
            sb.append("  ").append(seats.get(0).type()).append('\n');
        });
        return sb.toString();
    }

    @Override
    public String toString() {
        return id + " " + movie.title() + " @ " + screen.name() + " " + startsAt;
    }
}
