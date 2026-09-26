package com.lld.states.atm.model;

/**
 * Anything the ATM refuses to do, with a message fit for the screen.
 * {@link #isInvalidState()} distinguishes "wrong button for this screen" from a declined transaction.
 */
public class AtmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean invalidState;

    private AtmException(String message, boolean invalidState) {
        super(message);
        this.invalidState = invalidState;
    }

    public static AtmException invalidState(String action, String state) {
        return new AtmException("Cannot " + action + " while " + state, true);
    }

    public static AtmException declined(String reason) {
        return new AtmException(reason, false);
    }

    public boolean isInvalidState() {
        return invalidState;
    }
}
