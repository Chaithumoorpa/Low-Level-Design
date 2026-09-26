package com.lld.states.coffee.payment;

import java.util.HashMap;
import java.util.Map;

/** Card processor stand-in for the demo and the tests: cards with balances, refunds by id. */
public class InMemoryPaymentGateway implements PaymentGateway {

    private final Map<String, Integer> balances = new HashMap<>();
    private final Map<String, Object[]> transactions = new HashMap<>();   // id -> {token, amount, refunded}
    private int nextId = 1;

    public void addCard(String token, int balance) {
        balances.put(token, balance);
    }

    @Override
    public synchronized String charge(String cardToken, int amount) {
        Integer balance = balances.get(cardToken);
        if (balance == null || balance < amount) {
            return null;
        }
        balances.put(cardToken, balance - amount);
        String id = "TX" + nextId++;
        transactions.put(id, new Object[]{cardToken, amount, false});
        return id;
    }

    @Override
    public synchronized void refund(String transactionId) {
        Object[] tx = transactions.get(transactionId);
        if (tx == null || (boolean) tx[2]) {
            return;                                  // unknown or already refunded: idempotent
        }
        balances.merge((String) tx[0], (Integer) tx[1], Integer::sum);
        tx[2] = true;
    }

    public synchronized int balance(String token) {
        return balances.getOrDefault(token, 0);
    }
}
