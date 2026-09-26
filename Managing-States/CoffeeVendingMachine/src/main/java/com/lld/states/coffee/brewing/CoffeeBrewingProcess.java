package com.lld.states.coffee.brewing;

import com.lld.states.coffee.beverage.Beverage;
import com.lld.states.coffee.ingredient.Ingredient;

import java.util.function.Consumer;

/** Coffee drinks: grind the beans, then extract under pressure. */
public class CoffeeBrewingProcess extends BrewingProcess {

    public CoffeeBrewingProcess(BrewerHardware hardware) {
        super(hardware);
    }

    @Override
    protected void prepareBase(Beverage drink, Consumer<String> progress) {
        int beans = drink.recipe().get(Ingredient.COFFEE_BEANS);
        step(progress, "Grinding " + beans + " g beans");
        step(progress, "Extracting " + (beans / 18) + " shot(s)");
    }
}
