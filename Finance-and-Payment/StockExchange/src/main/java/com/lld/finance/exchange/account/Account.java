package com.lld.finance.exchange.account;

import java.util.HashMap;
import java.util.Map;

/**
 * A trader's cash and holdings. Open orders <b>reserve</b> what they might need (cash for buys, shares
 * for sells), so a trader can never promise the same dollar or share twice. Available = total − reserved.
 * All methods are synchronized: one account may be touched by matching in several symbols at once.
 */
public final class Account {

    private final String traderId;
    private long cash;
    private long reservedCash;
    private final Map<String, Long> shares = new HashMap<>();
    private final Map<String, Long> reservedShares = new HashMap<>();

    public Account(String traderId, long cash) {
        this.traderId = traderId;
        this.cash = cash;
    }

    public String traderId() {
        return traderId;
    }

    public synchronized long cash() {
        return cash;
    }

    public synchronized long availableCash() {
        return cash - reservedCash;
    }

    public synchronized long reservedCash() {
        return reservedCash;
    }

    public synchronized long shares(String symbol) {
        return shares.getOrDefault(symbol, 0L);
    }

    public synchronized long availableShares(String symbol) {
        return shares(symbol) - reservedShares.getOrDefault(symbol, 0L);
    }

    public synchronized void deposit(String symbol, long quantity) {
        shares.merge(symbol, quantity, Long::sum);
    }

    // ---- reservations (engine only)

    public synchronized boolean reserveCash(long amount) {
        if (availableCash() < amount) {
            return false;
        }
        reservedCash += amount;
        return true;
    }

    public synchronized void releaseCash(long amount) {
        reservedCash -= amount;
    }

    public synchronized boolean reserveShares(String symbol, long quantity) {
        if (availableShares(symbol) < quantity) {
            return false;
        }
        reservedShares.merge(symbol, quantity, Long::sum);
        return true;
    }

    public synchronized void releaseShares(String symbol, long quantity) {
        reservedShares.merge(symbol, -quantity, Long::sum);
    }

    // ---- settlement (engine only)

    /** Pay {@code qty * price}; the reservation made at {@code reservedPerUnit} is consumed (surplus freed). */
    public synchronized void settleBuy(String symbol, long qty, long price, long reservedPerUnit) {
        cash -= qty * price;
        reservedCash -= qty * reservedPerUnit;
        shares.merge(symbol, qty, Long::sum);
    }

    public synchronized void settleSell(String symbol, long qty, long price) {
        shares.merge(symbol, -qty, Long::sum);
        reservedShares.merge(symbol, -qty, Long::sum);
        cash += qty * price;
    }
}
