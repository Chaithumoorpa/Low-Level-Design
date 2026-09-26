package com.lld.states.atm.machine;

import java.util.Map;

/** PIN verified: the customer may run any number of transactions, then take the card. */
final class AuthenticatedState implements AtmState {

    static final AuthenticatedState INSTANCE = new AuthenticatedState();

    private AuthenticatedState() {
    }

    @Override
    public AtmStatus status() {
        return AtmStatus.AUTHENTICATED;
    }

    @Override
    public Map<Integer, Integer> withdraw(Atm atm, long amount) {
        return atm.performWithdrawal(amount);
    }

    @Override
    public void deposit(Atm atm, Map<Integer, Integer> notes) {
        atm.performDeposit(notes);
    }

    @Override
    public long checkBalance(Atm atm) {
        return atm.performBalanceInquiry();
    }

    @Override
    public void ejectCard(Atm atm) {
        atm.endSession();
    }
}
