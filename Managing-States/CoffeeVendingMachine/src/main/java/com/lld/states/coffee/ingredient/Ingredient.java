package com.lld.states.coffee.ingredient;

/** What the machine stores in its tanks and hoppers, with the unit each is measured in. */
public enum Ingredient {
    WATER("ml"),
    MILK("ml"),
    COFFEE_BEANS("g"),
    CHOCOLATE("g"),
    SUGAR("g");

    private final String unit;

    Ingredient(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}
