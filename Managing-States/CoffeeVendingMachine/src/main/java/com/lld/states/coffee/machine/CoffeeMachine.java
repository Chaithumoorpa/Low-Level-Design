package com.lld.states.coffee.machine;

import com.lld.states.coffee.beverage.AddOn;
import com.lld.states.coffee.beverage.Beverage;
import com.lld.states.coffee.beverage.Drink;
import com.lld.states.coffee.brewing.BrewerHardware;
import com.lld.states.coffee.brewing.BrewingProcess;
import com.lld.states.coffee.ingredient.Ingredient;
import com.lld.states.coffee.ingredient.IngredientInventory;
import com.lld.states.coffee.payment.PaymentMethod;
import com.lld.states.coffee.payment.PaymentResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The coffee machine: context of the State pattern and facade for customers and staff.
 *
 * <pre>
 *        selectDrink            pay (approved)             done
 *   IDLE ───────────▶ SELECTING ──────────────▶ BREWING ──────────▶ IDLE
 *    ▲   ◀── cancel ──┘  ▲ addExtra / declined pay     │ every N cups ──▶ NEEDS_CLEANING ── runCleaning ──▶ IDLE
 *    │                   └───────────────────┘         │ fault or nothing makeable ──▶ OUT_OF_SERVICE
 *    └──────────── finishMaintenance ◀── OUT_OF_SERVICE ◀── startMaintenance (IDLE / NEEDS_CLEANING)
 * </pre>
 *
 * Paying follows a safe order: check ingredients for the WHOLE order (drink + extras) → charge →
 * consume ingredients (all or nothing) → brew; if brewing fails, refund.
 */
public final class CoffeeMachine {

    public static final int MAX_EXTRAS_PER_KIND = 3;
    private static final Map<Ingredient, Integer> CLEANING_WATER = Map.of(Ingredient.WATER, 200);

    private final IngredientInventory inventory;
    private final BrewerHardware hardware;
    private final int cleaningInterval;
    private final List<CoffeeMachineListener> listeners = new ArrayList<>();

    private MachineState state = States.IDLE;
    private Beverage order;
    private int cupsSinceCleaning;
    private int cupsServed;
    private long revenue;
    private boolean fault;

    public CoffeeMachine(IngredientInventory inventory, BrewerHardware hardware, int cleaningInterval) {
        if (cleaningInterval < 1) {
            throw new IllegalArgumentException("Cleaning interval must be at least 1 cup");
        }
        this.inventory = Objects.requireNonNull(inventory);
        this.hardware = Objects.requireNonNull(hardware);
        this.cleaningInterval = cleaningInterval;
        inventory.onLowLevel((ingredient, left) -> listeners.forEach(l -> l.onLowIngredient(ingredient, left)));
        if (!canMakeAnything()) {
            state = States.OUT_OF_SERVICE;
        }
    }

    public void addListener(CoffeeMachineListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ actions → current state

    public void selectDrink(Drink drink) {
        state.selectDrink(this, Objects.requireNonNull(drink));
    }

    public void addExtra(AddOn.Kind kind) {
        state.addExtra(this, Objects.requireNonNull(kind));
    }

    public void cancel() {
        state.cancel(this);
    }

    public Cup pay(PaymentMethod payment) {
        return state.pay(this, Objects.requireNonNull(payment));
    }

    public void runCleaning() {
        state.runCleaning(this);
    }

    public void startMaintenance() {
        state.startMaintenance(this);
    }

    public void refill(Ingredient ingredient, int amount) {
        state.refill(this, ingredient, amount);
    }

    public void finishMaintenance() {
        state.finishMaintenance(this);
    }

    // ------------------------------------------------------------------ queries

    public MachineStatus status() {
        return state.status();
    }

    /** Current order's description and price, or null when nothing is selected. */
    public Beverage currentOrder() {
        return order;
    }

    /** Menu with availability, based on current ingredient levels. */
    public Map<Drink, Boolean> menu() {
        Map<Drink, Boolean> menu = new LinkedHashMap<>();
        for (Drink d : Drink.values()) {
            menu.put(d, inventory.hasEnough(d.recipe()));
        }
        return menu;
    }

    public int cupsServed() {
        return cupsServed;
    }

    public long revenue() {
        return revenue;
    }

    public int cupsUntilCleaning() {
        return cleaningInterval - cupsSinceCleaning;
    }

    public IngredientInventory inventory() {
        return inventory;
    }

    // ------------------------------------------------------------------ helpers for the states

    void transitionTo(MachineState next) {
        MachineStatus from = state.status();
        state = next;
        listeners.forEach(l -> l.onStateChange(from, next.status()));
    }

    void startOrder(Drink drink) {
        Map<Ingredient, Integer> missing = inventory.missing(drink.recipe());
        if (!missing.isEmpty()) {
            throw CoffeeMachineException.declined(drink.description() + " is unavailable (low on "
                    + missing.keySet() + ")");
        }
        order = drink;
    }

    void addToOrder(AddOn.Kind kind) {
        if (AddOn.count(order, kind) >= MAX_EXTRAS_PER_KIND) {
            throw CoffeeMachineException.declined("At most " + MAX_EXTRAS_PER_KIND + " of each extra");
        }
        Beverage candidate = new AddOn(order, kind);
        Map<Ingredient, Integer> missing = inventory.missing(candidate.recipe());
        if (!missing.isEmpty()) {
            throw CoffeeMachineException.declined("Not enough " + missing.keySet() + " for that extra");
        }
        order = candidate;
    }

    void clearOrder() {
        order = null;
    }

    boolean canMakeAnything() {
        return menu().containsValue(true);
    }

    void clean() {
        if (inventory.hasEnough(CLEANING_WATER)) {
            inventory.consume(CLEANING_WATER);
        }
        cupsSinceCleaning = 0;
    }

    void clearFault() {
        fault = false;
    }

    Cup payAndBrew(PaymentMethod payment) {
        Beverage drink = order;
        if (!inventory.hasEnough(drink.recipe())) {           // another cup may have used them meanwhile
            throw CoffeeMachineException.declined("Ingredients ran out for " + drink.description());
        }
        PaymentResult charge = payment.charge(drink.price());
        if (!charge.approved()) {
            throw CoffeeMachineException.declined("Payment declined: " + charge.message());
        }

        transitionTo(States.BREWING);
        try {
            inventory.consume(drink.recipe());
            BrewingProcess.forDrink(drink, hardware).brew(drink, step -> listeners.forEach(l -> l.onBrewStep(step)));
        } catch (RuntimeException failure) {
            payment.refund(charge);
            order = null;
            fault = true;
            transitionTo(States.OUT_OF_SERVICE);
            throw CoffeeMachineException.declined("Something went wrong while brewing. You have been refunded.");
        }

        order = null;
        cupsServed++;
        cupsSinceCleaning++;
        revenue += drink.price();
        Cup cup = new Cup(drink.description(), drink.price(), charge);
        listeners.forEach(l -> l.onCupServed(cup));

        if (!canMakeAnything()) {
            transitionTo(States.OUT_OF_SERVICE);
        } else if (cupsSinceCleaning >= cleaningInterval) {
            transitionTo(States.NEEDS_CLEANING);
        } else {
            transitionTo(States.IDLE);
        }
        return cup;
    }
}
