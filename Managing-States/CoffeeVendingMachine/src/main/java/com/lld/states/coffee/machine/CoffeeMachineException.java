package com.lld.states.coffee.machine;

/** A refusal for the display. {@link #isInvalidState()} marks "not now" as opposed to "not possible". */
public class CoffeeMachineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean invalidState;

    private CoffeeMachineException(String message, boolean invalidState) {
        super(message);
        this.invalidState = invalidState;
    }

    public static CoffeeMachineException invalidState(String action, String state) {
        return new CoffeeMachineException("Cannot " + action + " while " + state, true);
    }

    public static CoffeeMachineException declined(String reason) {
        return new CoffeeMachineException(reason, false);
    }

    public boolean isInvalidState() {
        return invalidState;
    }
}
