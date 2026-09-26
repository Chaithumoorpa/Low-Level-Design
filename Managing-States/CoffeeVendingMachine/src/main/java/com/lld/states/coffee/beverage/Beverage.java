package com.lld.states.coffee.beverage;

import com.lld.states.coffee.ingredient.Ingredient;

import java.util.Map;

/**
 * Anything the machine can pour. Base drinks and add-ons share this interface, so an add-on can
 * wrap a drink and still be "a drink" (Decorator pattern).
 */
public interface Beverage {

    String description();

    /** Price in cents. */
    int price();

    /** Ingredients needed, e.g. {WATER=30, COFFEE_BEANS=18, MILK=150}. */
    Map<Ingredient, Integer> recipe();

    /** Whether the brewing process must grind and extract coffee. */
    boolean containsCoffee();
}
