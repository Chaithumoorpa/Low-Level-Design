package com.lld.booking.ride.model;

public final class Rider {

    private final String id;
    private final String name;
    private final RatingTally rating = new RatingTally();

    public Rider(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public RatingTally rating() {
        return rating;
    }

    @Override
    public String toString() {
        return name;
    }
}
