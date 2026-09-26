package com.lld.booking.movie.model;

public record Movie(String id, String title, int minutes, String language) {

    public Movie {
        if (minutes <= 0) {
            throw new IllegalArgumentException("Running time must be positive");
        }
    }
}
