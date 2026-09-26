package com.lld.finance.payments.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Double-entry bookkeeping. Every money movement is one transaction of several entries whose amounts
 * sum to <b>zero</b> (per currency): money is never created or lost, only moved between accounts.
 * Sign convention: positive = debit (an asset for us / money owed to us), negative = credit (money we owe).
 *
 * <p>Accounts used: {@code processor:<id>} (what a processor will send us),
 * {@code merchant:<id>} (what we owe a merchant, negative), {@code fees} (our revenue, negative),
 * {@code bank} (money paid out).
 */
public final class Ledger {

    public record Entry(long txn, String reference, String account, long amountMinor, String currency) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Long> balances = new HashMap<>();
    private long txnSeq;

    /** Posts entries atomically. {@code legs}: account -> amount; must sum to zero. */
    public synchronized long post(String reference, String currency, Map<String, Long> legs) {
        long sum = legs.values().stream().mapToLong(Long::longValue).sum();
        if (sum != 0) {
            throw new IllegalStateException("Unbalanced transaction " + reference + ": " + legs);
        }
        long txn = ++txnSeq;
        legs.forEach((account, amount) -> {
            if (amount != 0) {
                entries.add(new Entry(txn, reference, account, amount, currency));
                balances.merge(account + "|" + currency, amount, Long::sum);
            }
        });
        return txn;
    }

    public synchronized long balance(String account, String currency) {
        return balances.getOrDefault(account + "|" + currency, 0L);
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Sum over all accounts (must always be zero per currency). */
    public synchronized long total(String currency) {
        return entries.stream().filter(e -> e.currency().equals(currency)).mapToLong(Entry::amountMinor).sum();
    }
}
