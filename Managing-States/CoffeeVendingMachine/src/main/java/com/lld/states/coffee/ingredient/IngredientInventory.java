package com.lld.states.coffee.ingredient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Tanks and hoppers with capacities and current levels.
 *
 * <p>{@link #consume} is <b>all or nothing</b>: it first checks every ingredient of the recipe and
 * only then takes any, so a latte is never half-made because the milk ran out after the coffee was
 * used. Crossing a low-level threshold notifies listeners once (until refilled).
 */
public class IngredientInventory {

    private final Map<Ingredient, Integer> capacity = new EnumMap<>(Ingredient.class);
    private final Map<Ingredient, Integer> level = new EnumMap<>(Ingredient.class);
    private final Map<Ingredient, Integer> lowThreshold = new EnumMap<>(Ingredient.class);
    private final Map<Ingredient, Boolean> lowAlerted = new EnumMap<>(Ingredient.class);
    private final List<BiConsumer<Ingredient, Integer>> lowLevelListeners = new ArrayList<>();

    /** @param capacities how much each container holds; thresholds default to 20% of capacity */
    public IngredientInventory(Map<Ingredient, Integer> capacities) {
        for (Ingredient i : Ingredient.values()) {
            int cap = capacities.getOrDefault(i, 0);
            if (cap < 0) {
                throw new IllegalArgumentException("Negative capacity for " + i);
            }
            capacity.put(i, cap);
            level.put(i, 0);
            lowThreshold.put(i, cap / 5);
            lowAlerted.put(i, false);
        }
    }

    public void onLowLevel(BiConsumer<Ingredient, Integer> listener) {
        lowLevelListeners.add(listener);
    }

    public synchronized boolean hasEnough(Map<Ingredient, Integer> recipe) {
        return missing(recipe).isEmpty();
    }

    /** Ingredients that are short for this recipe, with how much is missing. */
    public synchronized Map<Ingredient, Integer> missing(Map<Ingredient, Integer> recipe) {
        Map<Ingredient, Integer> shortBy = new EnumMap<>(Ingredient.class);
        recipe.forEach((i, need) -> {
            int have = level.get(i);
            if (have < need) {
                shortBy.put(i, need - have);
            }
        });
        return shortBy;
    }

    /** All or nothing. @throws IllegalStateException if anything is short (nothing is taken then) */
    public synchronized void consume(Map<Ingredient, Integer> recipe) {
        Map<Ingredient, Integer> shortBy = missing(recipe);
        if (!shortBy.isEmpty()) {
            throw new IllegalStateException("Not enough " + shortBy.keySet());
        }
        recipe.forEach((i, need) -> {
            int left = level.merge(i, -need, Integer::sum);
            if (left <= lowThreshold.get(i) && !lowAlerted.get(i) && capacity.get(i) > 0) {
                lowAlerted.put(i, true);
                lowLevelListeners.forEach(l -> l.accept(i, left));
            }
        });
    }

    /** @return how much was actually added (never above capacity) */
    public synchronized int refill(Ingredient ingredient, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative refill");
        }
        int before = level.get(ingredient);
        int after = Math.min(capacity.get(ingredient), before + amount);
        level.put(ingredient, after);
        if (after > lowThreshold.get(ingredient)) {
            lowAlerted.put(ingredient, false);
        }
        return after - before;
    }

    public synchronized void fillAll() {
        for (Ingredient i : Ingredient.values()) {
            refill(i, capacity.get(i));
        }
    }

    public synchronized int level(Ingredient ingredient) {
        return level.get(ingredient);
    }

    public synchronized Map<Ingredient, Integer> levels() {
        return Collections.unmodifiableMap(new EnumMap<>(level));
    }
}
