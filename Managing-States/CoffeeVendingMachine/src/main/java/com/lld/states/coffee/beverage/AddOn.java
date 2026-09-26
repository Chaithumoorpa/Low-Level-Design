package com.lld.states.coffee.beverage;

import com.lld.states.coffee.ingredient.Ingredient;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Decorator: wraps a beverage and adds price, ingredients and a description suffix.
 * Decorators stack: {@code new AddOn(new AddOn(LATTE, EXTRA_SHOT), SUGAR)}.
 */
public final class AddOn implements Beverage {

    /** The available extras. */
    public enum Kind {
        EXTRA_SHOT("extra shot", 60, Map.of(Ingredient.WATER, 30, Ingredient.COFFEE_BEANS, 18)),
        EXTRA_MILK("extra milk", 40, Map.of(Ingredient.MILK, 60)),
        SUGAR("sugar", 0, Map.of(Ingredient.SUGAR, 5)),
        CHOCOLATE_DRIZZLE("chocolate drizzle", 50, Map.of(Ingredient.CHOCOLATE, 10));

        private final String label;
        private final int price;
        private final Map<Ingredient, Integer> extra;

        Kind(String label, int price, Map<Ingredient, Integer> extra) {
            this.label = label;
            this.price = price;
            this.extra = extra;
        }
    }

    private final Beverage inner;
    private final Kind kind;

    public AddOn(Beverage inner, Kind kind) {
        this.inner = inner;
        this.kind = kind;
    }

    @Override
    public String description() {
        return inner.description() + " + " + kind.label;
    }

    @Override
    public int price() {
        return inner.price() + kind.price;
    }

    @Override
    public Map<Ingredient, Integer> recipe() {
        Map<Ingredient, Integer> total = new EnumMap<>(Ingredient.class);
        total.putAll(inner.recipe());
        kind.extra.forEach((i, amount) -> total.merge(i, amount, Integer::sum));
        return Collections.unmodifiableMap(total);
    }

    @Override
    public boolean containsCoffee() {
        return inner.containsCoffee() || kind.extra.containsKey(Ingredient.COFFEE_BEANS);
    }

    /** How many times this kind of add-on is already stacked on the drink. */
    public static int count(Beverage beverage, Kind kind) {
        int n = 0;
        for (Beverage b = beverage; b instanceof AddOn a; b = a.inner) {
            if (a.kind == kind) {
                n++;
            }
        }
        return n;
    }
}
