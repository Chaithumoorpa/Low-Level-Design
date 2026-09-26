package com.lld.finance.splitwise.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Ana paid $90 for dinner, split between Ana, Raj and Kai." {@code owed} says how much of the total
 * each participant consumed (the payer's own share included); it always sums to {@code totalCents}.
 */
public record Expense(String id, String groupId, String description, String payerId, long totalCents,
                      Map<String, Long> owed, String splitLabel, String createdBy, Instant createdAt) {

    public Expense {
        owed = new LinkedHashMap<>(owed);
    }

    @Override
    public Map<String, Long> owed() {
        return new LinkedHashMap<>(owed);
    }
}
