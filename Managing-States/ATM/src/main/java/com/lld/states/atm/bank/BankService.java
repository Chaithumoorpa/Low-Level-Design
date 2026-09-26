package com.lld.states.atm.bank;

import com.lld.states.atm.model.Card;

/**
 * The ATM's view of the bank's core system (in reality a network call through a card network).
 * The ATM never sees PINs in storage, balances beyond what it asks for, or other accounts.
 * Implementations must be thread-safe: the same account can be used by many ATMs at once.
 */
public interface BankService {

    boolean isCardBlocked(Card card);

    /** Checks the PIN; blocks the card after too many consecutive failures. */
    AuthResult authenticate(Card card, String pin);

    long balance(Card card);

    /**
     * Takes money out of the card's account, enforcing funds and the daily limit.
     *
     * @throws com.lld.states.atm.model.AtmException if declined
     */
    void debit(Card card, long amount);

    void credit(Card card, long amount);

    /** Reverses a debit that could not be completed: money back and the daily allowance back. */
    void refund(Card card, long amount);
}
