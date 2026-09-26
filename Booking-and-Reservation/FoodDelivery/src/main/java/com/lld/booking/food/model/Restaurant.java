package com.lld.booking.food.model;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A kitchen with a menu, opening hours, a typical preparation time and a minimum order. */
public final class Restaurant {

    private final String id;
    private final String name;
    private final Location location;
    private final int prepMinutes;
    private final LocalTime opens;
    private final LocalTime closes;
    private final long minOrderCents;
    private final Map<String, MenuItem> menu = new LinkedHashMap<>();
    private final Set<String> soldOut = new HashSet<>();
    private final RatingTally rating = new RatingTally();
    private boolean paused;

    public Restaurant(String id, String name, Location location, int prepMinutes, LocalTime opens, LocalTime closes,
                      long minOrderCents, List<MenuItem> items) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.prepMinutes = prepMinutes;
        this.opens = opens;
        this.closes = closes;
        this.minOrderCents = minOrderCents;
        items.forEach(i -> menu.put(i.id(), i));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Location location() {
        return location;
    }

    public int prepMinutes() {
        return prepMinutes;
    }

    public long minOrderCents() {
        return minOrderCents;
    }

    public Optional<MenuItem> item(String itemId) {
        return Optional.ofNullable(menu.get(itemId));
    }

    public boolean available(String itemId) {
        return menu.containsKey(itemId) && !soldOut.contains(itemId);
    }

    public void setAvailable(String itemId, boolean available) {
        if (available) {
            soldOut.remove(itemId);
        } else {
            soldOut.add(itemId);
        }
    }

    /** Within opening hours and not paused (e.g. too busy). */
    public boolean isOpenAt(LocalTime t) {
        return !paused && !t.isBefore(opens) && t.isBefore(closes);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public RatingTally rating() {
        return rating;
    }

    @Override
    public String toString() {
        return name;
    }
}
