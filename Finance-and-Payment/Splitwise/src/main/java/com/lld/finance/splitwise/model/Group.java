package com.lld.finance.splitwise.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** People who share expenses (a trip, a flat). Balances are computed per group. */
public final class Group {

    private final String id;
    private final String name;
    private final Set<String> members = new LinkedHashSet<>();

    public Group(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Set<String> members() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(members));   // join order
    }

    public boolean has(String userId) {
        return members.contains(userId);
    }

    public void add(String userId) {
        members.add(userId);
    }

    public void remove(String userId) {
        members.remove(userId);
    }
}
