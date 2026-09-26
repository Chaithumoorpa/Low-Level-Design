package com.lld.states.atm.model;

import java.time.Instant;

/**
 * One line of the ATM's journal (what a printed receipt or an audit log shows).
 *
 * @param amount  0 for balance inquiries
 * @param balance account balance after the operation, when known (-1 otherwise)
 */
public record TransactionRecord(long id, Instant time, String maskedCard, TransactionType type,
                                long amount, boolean success, long balance, String message) {

    @Override
    public String toString() {
        return String.format("#%d %s %-15s %8d %s%s", id, maskedCard, type, amount,
                success ? "OK" : "FAILED: " + message, balance >= 0 ? " (balance " + balance + ")" : "");
    }
}
