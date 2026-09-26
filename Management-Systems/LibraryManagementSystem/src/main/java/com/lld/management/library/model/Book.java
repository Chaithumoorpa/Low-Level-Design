package com.lld.management.library.model;

import java.util.List;

/**
 * A title in the catalogue (what you search for). The library may own several physical
 * {@link BookCopy copies} of it (what you carry home).
 *
 * @param priceCents replacement cost, charged if a copy is lost
 */
public record Book(String isbn, String title, List<String> authors, String subject, int year, long priceCents) {

    public Book {
        if (isbn == null || isbn.isBlank()) {
            throw new IllegalArgumentException("ISBN is required");
        }
        authors = List.copyOf(authors);
    }

    @Override
    public String toString() {
        return "'" + title + "' by " + String.join(", ", authors);
    }
}
