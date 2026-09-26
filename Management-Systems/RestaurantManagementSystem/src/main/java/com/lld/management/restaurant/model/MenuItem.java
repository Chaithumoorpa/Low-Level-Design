package com.lld.management.restaurant.model;

/**
 * A dish or drink on the menu. {@code station} says which part of the kitchen makes it, so an order
 * can be split into one ticket per station (grill, cold kitchen, bar...).
 */
public record MenuItem(String id, String name, Category category, Station station, long priceCents) {

    public enum Category {
        STARTER, MAIN, DESSERT, DRINK
    }

    public enum Station {
        GRILL, COLD, PASTRY, BAR
    }

    public MenuItem {
        if (priceCents < 0) {
            throw new IllegalArgumentException("Negative price");
        }
    }
}
