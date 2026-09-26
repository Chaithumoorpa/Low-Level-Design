package com.lld.states.coffee.machine;

import com.lld.states.coffee.ingredient.Ingredient;

/** Observer: display, progress bar, supplier alerts, sales reports. */
public interface CoffeeMachineListener {

    default void onStateChange(MachineStatus from, MachineStatus to) {
    }

    default void onBrewStep(String step) {
    }

    default void onLowIngredient(Ingredient ingredient, int levelLeft) {
    }

    default void onCupServed(Cup cup) {
    }
}
