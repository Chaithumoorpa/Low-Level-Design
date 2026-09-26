package com.lld.management.restaurant.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Everything a seated party orders and pays, from sitting down until the bill is settled. */
public final class Tab {

    private final String id;
    private final Table table;
    private final int partySize;
    private final LocalDateTime openedAt;
    private final List<OrderItem> items = new ArrayList<>();
    private final List<Long> payments = new ArrayList<>();
    private LocalDateTime closedAt;

    public Tab(String id, Table table, int partySize, LocalDateTime openedAt) {
        this.id = id;
        this.table = table;
        this.partySize = partySize;
        this.openedAt = openedAt;
    }

    public String id() {
        return id;
    }

    public Table table() {
        return table;
    }

    public int partySize() {
        return partySize;
    }

    public LocalDateTime openedAt() {
        return openedAt;
    }

    public List<OrderItem> items() {
        return List.copyOf(items);
    }

    public void add(OrderItem item) {
        items.add(item);
    }

    public long paidCents() {
        return payments.stream().mapToLong(Long::longValue).sum();
    }

    public void addPayment(long cents) {
        payments.add(cents);
    }

    public boolean isOpen() {
        return closedAt == null;
    }

    public void close(LocalDateTime at) {
        closedAt = at;
    }

    @Override
    public String toString() {
        return id + " at " + table.id() + " for " + partySize + ", seated " + openedAt.toLocalTime();
    }
}
