package com.lld.states.coffee.brewing;

import com.lld.states.coffee.beverage.Beverage;
import com.lld.states.coffee.ingredient.Ingredient;

import java.util.function.Consumer;

/**
 * Template Method: the fixed order of steps for making any drink. Subclasses fill in the steps that
 * differ (how the base is made); the rest is shared.
 *
 * <pre>
 *   heat water → prepare base (grind + extract, or mix powder) → steam milk? → add sugar? → pour
 * </pre>
 * Each step is reported to a progress callback, which the machine forwards to the display.
 */
public abstract class BrewingProcess {

    private final BrewerHardware hardware;

    protected BrewingProcess(BrewerHardware hardware) {
        this.hardware = hardware;
    }

    /** The template method. Final so the order can never be changed by a subclass. */
    public final void brew(Beverage drink, Consumer<String> progress) {
        step(progress, "Heating water");
        prepareBase(drink, progress);
        if (drink.recipe().containsKey(Ingredient.MILK)) {
            step(progress, "Steaming " + drink.recipe().get(Ingredient.MILK) + " ml milk");
        }
        if (drink.recipe().containsKey(Ingredient.SUGAR)) {
            step(progress, "Adding " + drink.recipe().get(Ingredient.SUGAR) + " g sugar");
        }
        step(progress, "Pouring " + drink.description());
    }

    /** The one step that varies between drink families. */
    protected abstract void prepareBase(Beverage drink, Consumer<String> progress);

    protected final void step(Consumer<String> progress, String description) {
        hardware.perform(description);            // may throw: a hardware fault mid-brew
        progress.accept(description);
    }

    /** Picks the right process for a drink. */
    public static BrewingProcess forDrink(Beverage drink, BrewerHardware hardware) {
        return drink.containsCoffee() ? new CoffeeBrewingProcess(hardware) : new PowderBrewingProcess(hardware);
    }
}
