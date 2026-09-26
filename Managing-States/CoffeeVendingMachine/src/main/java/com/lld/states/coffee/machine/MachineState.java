package com.lld.states.coffee.machine;

import com.lld.states.coffee.beverage.AddOn;
import com.lld.states.coffee.beverage.Drink;
import com.lld.states.coffee.ingredient.Ingredient;
import com.lld.states.coffee.payment.PaymentMethod;

/** State pattern: every button, "not allowed" by default; each state enables its own. */
interface MachineState {

    MachineStatus status();

    default void selectDrink(CoffeeMachine m, Drink drink) {
        throw notHere("select a drink");
    }

    default void addExtra(CoffeeMachine m, AddOn.Kind kind) {
        throw notHere("add extras");
    }

    default void cancel(CoffeeMachine m) {
        throw notHere("cancel");
    }

    default Cup pay(CoffeeMachine m, PaymentMethod payment) {
        throw notHere("pay");
    }

    default void runCleaning(CoffeeMachine m) {
        throw notHere("run a cleaning cycle");
    }

    default void startMaintenance(CoffeeMachine m) {
        throw notHere("start maintenance");
    }

    default void refill(CoffeeMachine m, Ingredient ingredient, int amount) {
        throw notHere("refill");
    }

    default void finishMaintenance(CoffeeMachine m) {
        throw notHere("finish maintenance");
    }

    private CoffeeMachineException notHere(String action) {
        return CoffeeMachineException.invalidState(action, status().name());
    }
}
