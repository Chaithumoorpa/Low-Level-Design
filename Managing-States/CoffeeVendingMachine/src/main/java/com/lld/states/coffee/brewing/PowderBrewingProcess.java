package com.lld.states.coffee.brewing;

import com.lld.states.coffee.beverage.Beverage;

import java.util.function.Consumer;

/** Non-coffee drinks (hot chocolate): no grinder, just mix the powder with hot water. */
public class PowderBrewingProcess extends BrewingProcess {

    public PowderBrewingProcess(BrewerHardware hardware) {
        super(hardware);
    }

    @Override
    protected void prepareBase(Beverage drink, Consumer<String> progress) {
        step(progress, "Mixing chocolate powder");
    }
}
