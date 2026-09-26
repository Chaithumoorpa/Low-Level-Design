package com.lld.states.vending.machine;

import com.lld.states.vending.model.Money;
import com.lld.states.vending.model.Product;
import com.lld.states.vending.model.Slot;
import com.lld.states.vending.model.VendResult;
import com.lld.states.vending.model.VendingException;
import com.lld.states.vending.money.ChangeMaker;
import com.lld.states.vending.money.CoinBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The machine: <b>context</b> of the State pattern and facade for customers and operators.
 *
 * <pre>
 *        insertCoin              selectProduct (paid, change possible)
 *   IDLE ──────────▶ HAS_MONEY ─────────────────────▶ DISPENSING ──▶ IDLE
 *    ▲  ◀── cancel (coins back) ──┘  ▲ insertCoin / declined select      │
 *    │                               └───────────────┘                   ├─▶ OUT_OF_SERVICE (jam or all sold out)
 *    └── finishMaintenance ── OUT_OF_SERVICE ◀── startMaintenance (only from IDLE)
 * </pre>
 *
 * Money design: the customer's coins stay in <b>escrow</b> until a sale completes, so cancel returns
 * the very same coins, and change is planned from the float PLUS the escrowed coins BEFORE the motor
 * runs. Coins the machine refuses or refunds after a jam go to the <b>coin return</b>.
 */
public class VendingMachine {

    private final Map<String, Slot> slots;
    private final CoinBox coinBox;
    private final ProductDispenser dispenser;
    private final List<VendingListener> listeners = new ArrayList<>();
    private final Map<Integer, Integer> escrow = new TreeMap<>();
    private final Map<Integer, Integer> coinReturn = new TreeMap<>();
    private final Map<String, Integer> unitsSold = new TreeMap<>();

    private VendingState state = IdleState.INSTANCE;
    private long revenue;
    private boolean fault;

    private VendingMachine(Builder b) {
        this.slots = Collections.unmodifiableMap(new LinkedHashMap<>(b.slots));
        this.coinBox = new CoinBox(b.denominations);
        this.coinBox.add(b.initialCoins);
        this.dispenser = b.dispenser;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void addListener(VendingListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ actions → current state

    /** @return the balance after the coin, if accepted */
    public long insertCoin(int denomination) {
        state.insertCoin(this, denomination);
        return balance();
    }

    public VendResult selectProduct(String slotCode) {
        return state.selectProduct(this, Objects.requireNonNull(slotCode));
    }

    /** @return the escrowed coins, handed back unchanged */
    public Map<Integer, Integer> cancel() {
        return state.cancel(this);
    }

    public void startMaintenance() {
        state.startMaintenance(this);
    }

    public void restock(String slotCode, int count) {
        state.restock(this, slotCode, count);
    }

    public void setPrice(String slotCode, int price) {
        state.setPrice(this, slotCode, price);
    }

    public void loadCoins(Map<Integer, Integer> coins) {
        state.loadCoins(this, coins);
    }

    public Map<Integer, Integer> collectCash() {
        return state.collectCash(this);
    }

    public void finishMaintenance() {
        state.finishMaintenance(this);
    }

    /** The customer takes whatever is in the coin return (rejected coins, refunds after a jam). */
    public Map<Integer, Integer> takeCoinReturn() {
        Map<Integer, Integer> coins = new TreeMap<>(coinReturn);
        coinReturn.clear();
        return coins;
    }

    // ------------------------------------------------------------------ queries

    public VendingStatus status() {
        return state.status();
    }

    public long balance() {
        return Money.total(escrow);
    }

    public List<Slot> slots() {
        return List.copyOf(slots.values());
    }

    public long revenue() {
        return revenue;
    }

    public Map<String, Integer> unitsSold() {
        return Collections.unmodifiableMap(unitsSold);
    }

    public long coinsInMachine() {
        return coinBox.total();
    }

    // ------------------------------------------------------------------ helpers for the states

    Slot slot(String code) {
        Slot slot = slots.get(code);
        if (slot == null) {
            throw VendingException.declined("There is no slot " + code);
        }
        return slot;
    }

    CoinBox coinBox() {
        return coinBox;
    }

    boolean allSoldOut() {
        return slots.values().stream().allMatch(Slot::isSoldOut);
    }

    void clearFault() {
        fault = false;
    }

    void transitionTo(VendingState next) {
        VendingStatus from = state.status();
        state = next;
        listeners.forEach(l -> l.onStateChange(from, next.status()));
    }

    void acceptCoin(int denomination) {
        if (!coinBox.accepts(denomination)) {
            coinReturn.merge(denomination, 1, Integer::sum);          // falls straight through
            throw VendingException.declined(denomination + "c is not accepted; please take it from the coin return");
        }
        escrow.merge(denomination, 1, Integer::sum);
        listeners.forEach(l -> l.onBalanceChange(balance()));
    }

    Map<Integer, Integer> releaseEscrow() {
        Map<Integer, Integer> refund = new TreeMap<>(escrow);
        escrow.clear();
        listeners.forEach(l -> l.onBalanceChange(0));
        return refund;
    }

    /**
     * The order that keeps money safe:
     * <ol>
     *   <li>validate slot, stock and balance (a decline keeps the customer's balance),</li>
     *   <li>plan change from float + escrow; if impossible, decline BEFORE dispensing,</li>
     *   <li>DISPENSING: run the motor; if it jams, return the escrowed coins and go out of service,</li>
     *   <li>commit: escrow into the box, change out, stock down, sale recorded.</li>
     * </ol>
     */
    VendResult performVend(String slotCode) {
        Slot slot = slot(slotCode);
        if (slot.isSoldOut()) {
            throw VendingException.declined(slot.product().name() + " is sold out; choose another product or cancel");
        }
        int price = slot.product().price();
        long balance = balance();
        if (balance < price) {
            throw VendingException.declined("Insert " + Money.format(price - balance) + " more for "
                    + slot.product().name());
        }
        Map<Integer, Integer> change = ChangeMaker.makeChange(balance - price, coinBox.plus(escrow))
                .orElseThrow(() -> VendingException.declined(
                        "Cannot give change for " + Money.format(balance - price)
                                + ". Please use exact change or cancel."));

        transitionTo(DispensingState.INSTANCE);
        try {
            dispenser.dispense(slotCode);
        } catch (RuntimeException jam) {
            Map<Integer, Integer> refund = releaseEscrow();
            refund.forEach((d, n) -> coinReturn.merge(d, n, Integer::sum));
            fault = true;
            transitionTo(OutOfServiceState.INSTANCE);
            throw VendingException.declined("The product got stuck. Your " + Money.format(Money.total(refund))
                    + " is in the coin return.");
        }

        coinBox.add(escrow);
        coinBox.remove(change);
        escrow.clear();
        slot.takeOne();
        revenue += price;
        unitsSold.merge(slotCode, 1, Integer::sum);
        VendResult result = new VendResult(slotCode, slot.product(), change);
        listeners.forEach(l -> l.onBalanceChange(0));
        listeners.forEach(l -> l.onSale(result));
        if (slot.isSoldOut()) {
            listeners.forEach(l -> l.onSoldOut(slotCode));
        }
        transitionTo(allSoldOut() || fault ? OutOfServiceState.INSTANCE : IdleState.INSTANCE);
        return result;
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {
        private int[] denominations = {5, 10, 25, 100};
        private final Map<String, Slot> slots = new LinkedHashMap<>();
        private final Map<Integer, Integer> initialCoins = new HashMap<>();
        private ProductDispenser dispenser = ProductDispenser.ALWAYS_WORKS;

        public Builder acceptCoins(int... denominations) {
            this.denominations = denominations.clone();
            return this;
        }

        /** Adds a slot and fills it with {@code quantity} items. */
        public Builder slot(String code, String name, int price, int capacity, int quantity) {
            if (slots.containsKey(code)) {
                throw new IllegalArgumentException("Duplicate slot " + code);
            }
            Slot slot = new Slot(code, new Product(name, price), capacity);
            slot.restock(quantity);
            slots.put(code, slot);
            return this;
        }

        public Builder changeFloat(Map<Integer, Integer> coins) {
            coins.forEach((d, n) -> initialCoins.merge(d, n, Integer::sum));
            return this;
        }

        public Builder dispenser(ProductDispenser dispenser) {
            this.dispenser = Objects.requireNonNull(dispenser);
            return this;
        }

        public VendingMachine build() {
            if (slots.isEmpty()) {
                throw new IllegalStateException("A machine needs at least one slot");
            }
            VendingMachine machine = new VendingMachine(this);
            if (machine.allSoldOut()) {
                machine.state = OutOfServiceState.INSTANCE;
            }
            return machine;
        }
    }
}
