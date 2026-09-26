package com.lld.states.atm.machine;

/** What the screen shows; one value per state class. */
public enum AtmStatus {
    IDLE,              // "Please insert your card"
    CARD_INSERTED,     // "Enter your PIN"
    AUTHENTICATED,     // "Choose a transaction"
    OUT_OF_SERVICE     // "Temporarily out of service"
}
