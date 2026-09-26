package com.lld.states.coffee.brewing;

/** The boiler, grinder and pumps. Any step may fail (e.g. a blocked grinder); tests simulate that. */
@FunctionalInterface
public interface BrewerHardware {

    void perform(String step);

    BrewerHardware ALWAYS_WORKS = step -> { };
}
