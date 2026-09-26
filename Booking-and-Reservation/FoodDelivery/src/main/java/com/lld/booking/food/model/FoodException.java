package com.lld.booking.food.model;

/** Restaurant closed, item sold out, too far, wrong state... */
public class FoodException extends RuntimeException {

    public FoodException(String message) {
        super(message);
    }
}
