package com.lld.states.atm.machine;

import com.lld.states.atm.bank.BankService;
import com.lld.states.atm.cash.CashDispenser;
import com.lld.states.atm.model.AtmException;
import com.lld.states.atm.model.Card;
import com.lld.states.atm.model.TransactionRecord;
import com.lld.states.atm.model.TransactionType;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ATM: the <b>context</b> of the State pattern. Every public action is forwarded to the current
 * state, which either performs it (usually by calling back into the helpers below) or refuses it.
 *
 * <pre>
 *            insertCard               enterPin (ok)
 *   IDLE ─────────────────▶ CARD_INSERTED ─────────────▶ AUTHENTICATED ──┐ withdraw / deposit /
 *    ▲  ▲                        │  eject / blocked / unknown       │    ◀┘ balance (any number)
 *    │  └────────────────────────┘                                  │ eject
 *    │                     (cash left, no fault)                    ▼
 *    │ finishMaintenance ◀────────────── OUT_OF_SERVICE ◀── (cash empty or dispenser fault)
 *    └── startMaintenance (only from IDLE) ──▶
 * </pre>
 *
 * Single customer at a time (it is one physical machine), so the context itself needs no locking;
 * the bank handles concurrency across machines.
 */
public class Atm {

    private final String atmId;
    private final BankService bank;
    private final CashDispenser dispenser;
    private final long maxWithdrawalPerTransaction;
    private final Clock clock;
    private final List<AtmEventListener> listeners = new ArrayList<>();
    private final List<TransactionRecord> journal = new ArrayList<>();

    private AtmState state = IdleState.INSTANCE;
    private Card currentCard;
    private boolean hardwareFault;
    private long nextTransactionId = 1;

    public Atm(String atmId, BankService bank, CashDispenser dispenser, long maxWithdrawalPerTransaction, Clock clock) {
        this.atmId = Objects.requireNonNull(atmId);
        this.bank = Objects.requireNonNull(bank);
        this.dispenser = Objects.requireNonNull(dispenser);
        this.maxWithdrawalPerTransaction = maxWithdrawalPerTransaction;
        this.clock = Objects.requireNonNull(clock);
        if (dispenser.isEmpty()) {
            state = OutOfServiceState.INSTANCE;
        }
    }

    public void addListener(AtmEventListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ public actions → current state

    public void insertCard(Card card) {
        state.insertCard(this, Objects.requireNonNull(card));
    }

    public void enterPin(String pin) {
        state.enterPin(this, pin);
    }

    /** @return the notes handed out, denomination → count */
    public Map<Integer, Integer> withdraw(long amount) {
        return state.withdraw(this, amount);
    }

    public void deposit(Map<Integer, Integer> notes) {
        state.deposit(this, notes);
    }

    public long checkBalance() {
        return state.checkBalance(this);
    }

    public void ejectCard() {
        state.ejectCard(this);
    }

    public void startMaintenance() {
        state.startMaintenance(this);
    }

    public void refill(Map<Integer, Integer> notes) {
        state.refill(this, notes);
    }

    public void finishMaintenance() {
        state.finishMaintenance(this);
    }

    // ------------------------------------------------------------------ queries

    public AtmStatus status() {
        return state.status();
    }

    public List<TransactionRecord> journal() {
        return Collections.unmodifiableList(journal);
    }

    public long cashAvailable() {
        return dispenser.totalCash();
    }

    public String atmId() {
        return atmId;
    }

    // ------------------------------------------------------------------ helpers for the states

    BankService bank() {
        return bank;
    }

    CashDispenser dispenser() {
        return dispenser;
    }

    Card currentCard() {
        return currentCard;
    }

    void acceptCard(Card card) {
        currentCard = card;
    }

    void transitionTo(AtmState next) {
        AtmStatus from = state.status();
        state = next;
        listeners.forEach(l -> l.onStateChange(from, next.status()));
    }

    /** Card leaves the slot (or is kept); go idle, or offline if the machine can no longer serve. */
    void endSession() {
        currentCard = null;
        transitionTo(dispenser.isEmpty() || hardwareFault ? OutOfServiceState.INSTANCE : IdleState.INSTANCE);
    }

    void retainCard(Card card) {
        listeners.forEach(l -> l.onCardRetained(card));
    }

    void clearHardwareFault() {
        hardwareFault = false;
    }

    /**
     * Order matters, and it is the heart of this problem:
     * <ol>
     *   <li>validate locally (amount, limit),</li>
     *   <li>plan the notes BEFORE touching the account (don't debit what we cannot pay out),</li>
     *   <li>debit the bank (it checks funds and the daily limit atomically),</li>
     *   <li>dispense; if the hardware fails now, <b>refund</b> (compensating action).</li>
     * </ol>
     */
    Map<Integer, Integer> performWithdrawal(long amount) {
        try {
            if (amount <= 0) {
                throw AtmException.declined("Amount must be positive");
            }
            if (amount > maxWithdrawalPerTransaction) {
                throw AtmException.declined("Maximum per withdrawal is " + maxWithdrawalPerTransaction);
            }
            if (amount % dispenser.unit() != 0) {
                throw AtmException.declined("Amount must be a multiple of " + dispenser.unit());
            }
            Map<Integer, Integer> plan = dispenser.plan(amount).orElseThrow(() -> AtmException.declined(
                    "This ATM cannot dispense " + amount + " with the notes it has"));

            bank.debit(currentCard, amount);
            try {
                dispenser.dispense(plan);
            } catch (RuntimeException hardwareError) {
                bank.refund(currentCard, amount);
                hardwareFault = true;
                throw AtmException.declined("Cash could not be dispensed; your account was not charged");
            }
            record(TransactionType.WITHDRAWAL, amount, true, bank.balance(currentCard), "");
            return plan;
        } catch (AtmException declined) {
            record(TransactionType.WITHDRAWAL, amount, false, -1, declined.getMessage());
            throw declined;
        }
    }

    void performDeposit(Map<Integer, Integer> notes) {
        long total = 0;
        for (Map.Entry<Integer, Integer> e : notes.entrySet()) {
            if (!dispenser.accepts(e.getKey()) || e.getValue() < 0) {
                AtmException declined = AtmException.declined("Note " + e.getKey() + " is not accepted");
                record(TransactionType.DEPOSIT, 0, false, -1, declined.getMessage());
                throw declined;
            }
            total += (long) e.getKey() * e.getValue();
        }
        if (total <= 0) {
            throw AtmException.declined("No notes inserted");
        }
        dispenser.accept(notes);
        bank.credit(currentCard, total);
        record(TransactionType.DEPOSIT, total, true, bank.balance(currentCard), "");
    }

    long performBalanceInquiry() {
        long balance = bank.balance(currentCard);
        record(TransactionType.BALANCE_INQUIRY, 0, true, balance, "");
        return balance;
    }

    private void record(TransactionType type, long amount, boolean success, long balance, String message) {
        TransactionRecord r = new TransactionRecord(nextTransactionId++, clock.instant(),
                currentCard.masked(), type, amount, success, balance, message);
        journal.add(r);
        listeners.forEach(l -> l.onTransaction(r));
    }
}
