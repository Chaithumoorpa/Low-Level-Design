package com.lld.management.restaurant.model;

/** A request the restaurant refuses: no table, dish sold out, bill not settled... */
public class RestaurantException extends RuntimeException {

    public RestaurantException(String message) {
        super(message);
    }
}
