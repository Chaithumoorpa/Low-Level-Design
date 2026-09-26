package com.lld.booking.movie.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An auditorium's fixed seat layout, built row by row. */
public final class Screen {

    /** "Row C has 10 PREMIUM seats." */
    public record Row(char row, int seats, SeatType type) {
    }

    private final String id;
    private final String cinemaId;
    private final String name;
    private final Map<String, Seat> seats = new LinkedHashMap<>();
    private final Map<Character, List<Seat>> rows = new LinkedHashMap<>();

    public Screen(String id, String cinemaId, String name, List<Row> layout) {
        this.id = id;
        this.cinemaId = cinemaId;
        this.name = name;
        for (Row r : layout) {
            List<Seat> row = new ArrayList<>();
            for (int n = 1; n <= r.seats(); n++) {
                Seat s = new Seat(Seat.id(r.row(), n), r.row(), n, r.type());
                seats.put(s.id(), s);
                row.add(s);
            }
            rows.put(r.row(), List.copyOf(row));
        }
    }

    public String id() {
        return id;
    }

    public String cinemaId() {
        return cinemaId;
    }

    public String name() {
        return name;
    }

    public Optional<Seat> seat(String seatId) {
        return Optional.ofNullable(seats.get(seatId));
    }

    public List<Seat> seats() {
        return List.copyOf(seats.values());
    }

    public Map<Character, List<Seat>> rows() {
        return rows;
    }
}
