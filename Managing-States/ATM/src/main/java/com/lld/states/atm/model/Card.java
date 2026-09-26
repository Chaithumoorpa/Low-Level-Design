package com.lld.states.atm.model;

/** A physical card. Holds only the number; everything secret lives at the bank. */
public record Card(String number) {

    public Card {
        if (number == null || !number.matches("\\d{12,19}")) {
            throw new IllegalArgumentException("Card number must be 12-19 digits");
        }
    }

    /** Masked form for receipts and logs: **** **** **** 1234. */
    public String masked() {
        return "**** " + number.substring(number.length() - 4);
    }

    @Override
    public String toString() {
        return masked();
    }
}
