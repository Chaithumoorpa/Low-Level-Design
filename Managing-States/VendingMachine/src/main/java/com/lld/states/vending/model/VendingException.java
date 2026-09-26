package com.lld.states.vending.model;

/** A refusal with a message for the display; {@link #isInvalidState()} marks "wrong time for that button". */
public class VendingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean invalidState;

    private VendingException(String message, boolean invalidState) {
        super(message);
        this.invalidState = invalidState;
    }

    public static VendingException invalidState(String action, String state) {
        return new VendingException("Cannot " + action + " while " + state, true);
    }

    public static VendingException declined(String reason) {
        return new VendingException(reason, false);
    }

    public boolean isInvalidState() {
        return invalidState;
    }
}
