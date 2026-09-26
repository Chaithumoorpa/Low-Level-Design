package com.lld.states.coffee.beverage;

import com.lld.states.coffee.ingredient.Ingredient;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** The menu of base drinks: price and recipe. Add-ons are layered on top with decorators. */
public enum Drink implements Beverage {

    ESPRESSO("Espresso", 150, recipe(30, 0, 18, 0)),
    AMERICANO("Americano", 180, recipe(150, 0, 18, 0)),
    LATTE("Latte", 250, recipe(30, 180, 18, 0)),
    CAPPUCCINO("Cappuccino", 240, recipe(30, 120, 18, 0)),
    HOT_CHOCOLATE("Hot Chocolate", 220, recipe(50, 150, 0, 30));

    private final String displayName;
    private final int price;
    private final Map<Ingredient, Integer> recipe;

    Drink(String displayName, int price, Map<Ingredient, Integer> recipe) {
        this.displayName = displayName;
        this.price = price;
        this.recipe = recipe;
    }

    @Override
    public String description() {
        return displayName;
    }

    @Override
    public int price() {
        return price;
    }

    @Override
    public Map<Ingredient, Integer> recipe() {
        return recipe;
    }

    @Override
    public boolean containsCoffee() {
        return recipe.containsKey(Ingredient.COFFEE_BEANS);
    }

    private static Map<Ingredient, Integer> recipe(int water, int milk, int beans, int chocolate) {
        Map<Ingredient, Integer> r = new EnumMap<>(Ingredient.class);
        if (water > 0) {
            r.put(Ingredient.WATER, water);
        }
        if (milk > 0) {
            r.put(Ingredient.MILK, milk);
        }
        if (beans > 0) {
            r.put(Ingredient.COFFEE_BEANS, beans);
        }
        if (chocolate > 0) {
            r.put(Ingredient.CHOCOLATE, chocolate);
        }
        return Collections.unmodifiableMap(r);
    }
}
