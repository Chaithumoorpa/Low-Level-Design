package com.lld.management.library.model;

/** A request the library refuses: limit reached, copy not available, unpaid fines... */
public class LibraryException extends RuntimeException {

    public LibraryException(String message) {
        super(message);
    }
}
