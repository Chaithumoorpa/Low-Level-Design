package com.lld.states.atm.bank;

import com.lld.states.atm.model.AtmException;
import com.lld.states.atm.model.Card;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small bank for the demo and the tests.
 *
 * <ul>
 *   <li>PINs are stored only as salted SHA-256 hashes, and compared in constant time.</li>
 *   <li>3 consecutive wrong PINs block the card; a correct PIN resets the counter.</li>
 *   <li>Each account has a daily withdrawal limit, reset when the date changes.</li>
 *   <li>Every account operation locks that account only, so two ATMs debiting the same
 *       account at once can never overdraw it.</li>
 * </ul>
 */
public class InMemoryBank implements BankService {

    public static final int MAX_PIN_ATTEMPTS = 3;

    private static final class Account {
        long balance;
        final long dailyLimit;
        LocalDate day;
        long withdrawnToday;

        Account(long balance, long dailyLimit) {
            this.balance = balance;
            this.dailyLimit = dailyLimit;
        }
    }

    private static final class CardRecord {
        final Account account;
        final byte[] salt;
        final byte[] pinHash;
        int failedAttempts;
        boolean blocked;

        CardRecord(Account account, byte[] salt, byte[] pinHash) {
            this.account = account;
            this.salt = salt;
            this.pinHash = pinHash;
        }
    }

    private final Map<String, CardRecord> cards = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public InMemoryBank() {
        this(Clock.systemDefaultZone());
    }

    public InMemoryBank(Clock clock) {
        this.clock = clock;
    }

    /** Opens an account with one card. */
    public void openAccount(Card card, String pin, long balance, long dailyLimit) {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new IllegalArgumentException("PIN must be 4 digits");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        cards.put(card.number(), new CardRecord(new Account(balance, dailyLimit), salt, hash(salt, pin)));
    }

    @Override
    public boolean isCardBlocked(Card card) {
        CardRecord record = cards.get(card.number());
        return record != null && blocked(record);   // unknown cards are rejected at PIN time, not retained
    }

    @Override
    public AuthResult authenticate(Card card, String pin) {
        CardRecord record = cards.get(card.number());
        if (record == null) {
            return new AuthResult(AuthResult.Status.UNKNOWN_CARD, 0);
        }
        synchronized (record) {
            if (record.blocked) {
                return new AuthResult(AuthResult.Status.CARD_BLOCKED, 0);
            }
            boolean ok = pin != null && MessageDigest.isEqual(record.pinHash, hash(record.salt, pin));
            if (ok) {
                record.failedAttempts = 0;
                return new AuthResult(AuthResult.Status.SUCCESS, MAX_PIN_ATTEMPTS);
            }
            record.failedAttempts++;
            if (record.failedAttempts >= MAX_PIN_ATTEMPTS) {
                record.blocked = true;
                return new AuthResult(AuthResult.Status.CARD_BLOCKED, 0);
            }
            return new AuthResult(AuthResult.Status.WRONG_PIN, MAX_PIN_ATTEMPTS - record.failedAttempts);
        }
    }

    @Override
    public long balance(Card card) {
        Account a = account(card);
        synchronized (a) {
            return a.balance;
        }
    }

    @Override
    public void debit(Card card, long amount) {
        Account a = account(card);
        synchronized (a) {
            rollDay(a);
            if (amount > a.balance) {
                throw AtmException.declined("Insufficient funds");
            }
            if (a.withdrawnToday + amount > a.dailyLimit) {
                throw AtmException.declined("Daily limit exceeded (remaining today: "
                        + (a.dailyLimit - a.withdrawnToday) + ")");
            }
            a.balance -= amount;
            a.withdrawnToday += amount;
        }
    }

    @Override
    public void credit(Card card, long amount) {
        Account a = account(card);
        synchronized (a) {
            a.balance += amount;
        }
    }

    @Override
    public void refund(Card card, long amount) {
        Account a = account(card);
        synchronized (a) {
            rollDay(a);
            a.balance += amount;
            a.withdrawnToday = Math.max(0, a.withdrawnToday - amount);
        }
    }

    /** Branch staff action. */
    public void unblock(Card card) {
        CardRecord record = cards.get(card.number());
        synchronized (record) {
            record.blocked = false;
            record.failedAttempts = 0;
        }
    }

    private boolean blocked(CardRecord record) {
        synchronized (record) {
            return record.blocked;
        }
    }

    private Account account(Card card) {
        CardRecord record = cards.get(card.number());
        if (record == null) {
            throw AtmException.declined("Unknown card");
        }
        return record.account;
    }

    private void rollDay(Account a) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(a.day)) {
            a.day = today;
            a.withdrawnToday = 0;
        }
    }

    private static byte[] hash(byte[] salt, String pin) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            return sha.digest(pin.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
