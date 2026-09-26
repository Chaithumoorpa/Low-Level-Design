package com.lld.states.atm.bank;

/** Outcome of a PIN check. */
public record AuthResult(Status status, int attemptsLeft) {

    public enum Status {
        SUCCESS,
        WRONG_PIN,
        CARD_BLOCKED,
        UNKNOWN_CARD
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
