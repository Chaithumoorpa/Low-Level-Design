package com.lld.finance.splitwise.service;

import com.lld.finance.splitwise.model.Expense;
import com.lld.finance.splitwise.model.Group;
import com.lld.finance.splitwise.model.Money;
import com.lld.finance.splitwise.model.Settlement;
import com.lld.finance.splitwise.model.SplitwiseException;
import com.lld.finance.splitwise.model.Transfer;
import com.lld.finance.splitwise.model.User;
import com.lld.finance.splitwise.split.Split;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Facade: users, groups, expenses, settlements, balances.
 *
 * <p><b>Source of truth:</b> the list of expenses and settlements. Balances are never stored; they are
 * derived by replaying that list. So editing or deleting an expense can't leave balances out of step,
 * and the invariant "all balances in a group sum to zero" always holds.
 *
 * <p>Concurrency: one lock for the whole service (expense apps are write-light); balance queries
 * see a consistent snapshot.
 */
public final class SplitwiseService {

    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, Expense> expenses = new LinkedHashMap<>();           // active only
    private final List<Settlement> settlements = new ArrayList<>();
    private final Map<String, List<String>> activity = new LinkedHashMap<>();
    private final Clock clock;
    private long groupSeq;
    private long expenseSeq;
    private long settlementSeq;

    public SplitwiseService(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ users & groups

    public synchronized User addUser(String id, String name) {
        if (users.containsKey(id)) {
            throw new SplitwiseException("User " + id + " exists");
        }
        User u = new User(id, name);
        users.put(id, u);
        return u;
    }

    public synchronized Group createGroup(String name, String creatorId, String... memberIds) {
        requireUser(creatorId);
        Group g = new Group("G" + (++groupSeq), name);
        g.add(creatorId);
        for (String m : memberIds) {
            requireUser(m);
            g.add(m);
        }
        groups.put(g.id(), g);
        log(g.id(), name(creatorId) + " created the group");
        return g;
    }

    public synchronized void addMember(String groupId, String userId) {
        requireUser(userId);
        group(groupId).add(userId);
        log(groupId, name(userId) + " joined");
    }

    /** Only with a zero balance: nobody may walk away owing or being owed money. */
    public synchronized void removeMember(String groupId, String userId) {
        Group g = group(groupId);
        requireMember(g, userId);
        long balance = netBalances(groupId).getOrDefault(userId, 0L);
        if (balance != 0) {
            throw new SplitwiseException(name(userId) + " has an open balance of " + Money.format(balance) + " in " + g.name());
        }
        g.remove(userId);
        log(groupId, name(userId) + " left");
    }

    // ------------------------------------------------------------------ expenses

    public synchronized Expense addExpense(String groupId, String createdBy, String description, String payerId,
                                           long totalCents, Split split) {
        Expense e = build("E" + (++expenseSeq), groupId, createdBy, description, payerId, totalCents, split);
        expenses.put(e.id(), e);
        log(groupId, name(createdBy) + " added '" + description + "' " + Money.format(totalCents)
                + " paid by " + name(payerId) + ", split " + split.label());
        return e;
    }

    /** Replaces an expense's details; balances follow automatically because they are recomputed. */
    public synchronized Expense editExpense(String expenseId, String editorId, String description, String payerId,
                                            long totalCents, Split split) {
        Expense old = expense(expenseId);
        Expense updated = build(old.id(), old.groupId(), editorId, description, payerId, totalCents, split);
        expenses.put(old.id(), updated);
        log(old.groupId(), name(editorId) + " edited '" + old.description() + "': " + Money.format(old.totalCents())
                + " -> " + Money.format(totalCents));
        return updated;
    }

    public synchronized void deleteExpense(String expenseId, String byId) {
        Expense e = expense(expenseId);
        requireMember(group(e.groupId()), byId);
        expenses.remove(expenseId);
        log(e.groupId(), name(byId) + " deleted '" + e.description() + "'");
    }

    private Expense build(String id, String groupId, String createdBy, String description, String payerId,
                          long totalCents, Split split) {
        Group g = group(groupId);
        requireMember(g, createdBy);
        requireMember(g, payerId);
        if (totalCents <= 0) {
            throw new SplitwiseException("Amount must be positive");
        }
        Map<String, Long> owed = split.owed(totalCents);
        owed.keySet().forEach(u -> requireMember(g, u));
        return new Expense(id, groupId, description, payerId, totalCents, owed, split.label(), createdBy, clock.instant());
    }

    // ------------------------------------------------------------------ settling

    /** Records a real payment {@code from} → {@code to}. Any positive amount (it may follow a simplified plan). */
    public synchronized Settlement settleUp(String groupId, String fromId, String toId, long amountCents) {
        Group g = group(groupId);
        requireMember(g, fromId);
        requireMember(g, toId);
        if (fromId.equals(toId) || amountCents <= 0) {
            throw new SplitwiseException("A settlement needs two different people and a positive amount");
        }
        Settlement s = new Settlement("S" + (++settlementSeq), groupId, fromId, toId, amountCents, clock.instant());
        settlements.add(s);
        log(groupId, name(fromId) + " paid " + name(toId) + " " + Money.format(amountCents));
        return s;
    }

    // ------------------------------------------------------------------ balances (derived)

    /** user -> net cents in the group: positive = gets money back, negative = owes. Sums to zero. */
    public synchronized Map<String, Long> netBalances(String groupId) {
        Map<String, Long> net = new LinkedHashMap<>();
        group(groupId).members().forEach(m -> net.put(m, 0L));
        pairwise(groupId).forEach((debtor, creditors) -> creditors.forEach((creditor, amount) -> {
            net.merge(debtor, -amount, Long::sum);
            net.merge(creditor, amount, Long::sum);
        }));
        return net;
    }

    /** Who owes whom from their own dealings (each pair netted, loops cancelled), before simplification. */
    public synchronized List<Transfer> debts(String groupId) {
        List<Transfer> out = new ArrayList<>();
        pairwise(groupId).forEach((debtor, creditors) -> creditors.forEach((creditor, amount) -> {
            if (amount > 0) {
                out.add(new Transfer(debtor, creditor, amount));
            }
        }));
        return out;
    }

    /** The fewest-payments plan (greedy) to settle the whole group. */
    public synchronized List<Transfer> simplifiedDebts(String groupId) {
        return DebtSimplifier.simplify(netBalances(groupId));
    }

    /** Net between two people across all shared groups: positive = {@code other} owes {@code user}. */
    public synchronized long balanceBetween(String userId, String otherId) {
        long total = 0;
        for (Group g : groups.values()) {
            Map<String, Map<String, Long>> p = pairwise(g.id());
            total += p.getOrDefault(otherId, Map.of()).getOrDefault(userId, 0L);
            total -= p.getOrDefault(userId, Map.of()).getOrDefault(otherId, 0L);
        }
        return total;
    }

    /** Across every group the user is in. */
    public synchronized long overallBalance(String userId) {
        requireUser(userId);
        return groups.values().stream().filter(g -> g.has(userId))
                .mapToLong(g -> netBalances(g.id()).getOrDefault(userId, 0L)).sum();
    }

    public synchronized List<String> activity(String groupId) {
        group(groupId);
        return List.copyOf(activity.getOrDefault(groupId, List.of()));
    }

    public synchronized List<Expense> expenses(String groupId) {
        return expenses.values().stream().filter(e -> e.groupId().equals(groupId)).toList();
    }

    /**
     * Replays expenses and settlements into "debtor owes creditor" amounts, netted per pair so at most
     * one direction is positive. Sorted maps keep output deterministic.
     */
    private Map<String, Map<String, Long>> pairwise(String groupId) {
        Map<String, Map<String, Long>> raw = new TreeMap<>();
        for (Expense e : expenses.values()) {
            if (!e.groupId().equals(groupId)) {
                continue;
            }
            e.owed().forEach((participant, share) -> {
                if (!participant.equals(e.payerId()) && share > 0) {
                    add(raw, participant, e.payerId(), share);
                }
            });
        }
        for (Settlement s : settlements) {
            if (s.groupId().equals(groupId)) {
                add(raw, s.toId(), s.fromId(), s.amountCents());               // paying back = the other way round
            }
        }
        Map<String, Map<String, Long>> net = new TreeMap<>();
        raw.forEach((a, row) -> row.forEach((b, amount) -> {
            long reverse = raw.getOrDefault(b, Map.of()).getOrDefault(a, 0L);
            if (amount > reverse) {
                net.computeIfAbsent(a, k -> new TreeMap<>()).put(b, amount - reverse);
            }
        }));
        cancelCycles(net);
        return net;
    }

    /**
     * a owes b, b owes c, c owes a: subtracting the smallest amount around the loop changes nobody's
     * net position but removes a payment. Repeating until no loop is left means that once everyone's
     * net is zero (e.g. after settling via the simplified plan) no stale "a owes b" lines remain.
     */
    private static void cancelCycles(Map<String, Map<String, Long>> graph) {
        List<String> cycle;
        while (!(cycle = findCycle(graph)).isEmpty()) {
            long min = Long.MAX_VALUE;
            for (int i = 0; i < cycle.size(); i++) {
                min = Math.min(min, graph.get(cycle.get(i)).get(cycle.get((i + 1) % cycle.size())));
            }
            for (int i = 0; i < cycle.size(); i++) {
                String from = cycle.get(i);
                String to = cycle.get((i + 1) % cycle.size());
                long left = graph.get(from).get(to) - min;
                if (left == 0) {
                    graph.get(from).remove(to);
                    if (graph.get(from).isEmpty()) {
                        graph.remove(from);
                    }
                } else {
                    graph.get(from).put(to, left);
                }
            }
        }
    }

    /** DFS for any directed cycle; returns its nodes in order, or an empty list. */
    private static List<String> findCycle(Map<String, Map<String, Long>> graph) {
        Map<String, Integer> state = new HashMap<>();              // 1 = on stack, 2 = done
        List<String> stack = new ArrayList<>();
        for (String start : graph.keySet()) {
            List<String> found = dfs(start, graph, state, stack);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private static List<String> dfs(String node, Map<String, Map<String, Long>> graph, Map<String, Integer> state,
                                    List<String> stack) {
        Integer s = state.get(node);
        if (s != null && s == 2) {
            return List.of();
        }
        if (s != null && s == 1) {
            return new ArrayList<>(stack.subList(stack.indexOf(node), stack.size()));
        }
        state.put(node, 1);
        stack.add(node);
        for (String next : graph.getOrDefault(node, Map.of()).keySet()) {
            List<String> found = dfs(next, graph, state, stack);
            if (!found.isEmpty()) {
                return found;
            }
        }
        stack.remove(stack.size() - 1);
        state.put(node, 2);
        return List.of();
    }

    private static void add(Map<String, Map<String, Long>> m, String debtor, String creditor, long amount) {
        m.computeIfAbsent(debtor, k -> new TreeMap<>()).merge(creditor, amount, Long::sum);
    }

    // ------------------------------------------------------------------ helpers

    private void log(String groupId, String text) {
        activity.computeIfAbsent(groupId, k -> new ArrayList<>()).add(text);
    }

    private User requireUser(String id) {
        User u = users.get(id);
        if (u == null) {
            throw new SplitwiseException("No user " + id);
        }
        return u;
    }

    private void requireMember(Group g, String userId) {
        requireUser(userId);
        if (!g.has(userId)) {
            throw new SplitwiseException(name(userId) + " is not in " + g.name());
        }
    }

    public synchronized Group group(String id) {
        Group g = groups.get(id);
        if (g == null) {
            throw new SplitwiseException("No group " + id);
        }
        return g;
    }

    private Expense expense(String id) {
        Expense e = expenses.get(id);
        if (e == null) {
            throw new SplitwiseException("No expense " + id);
        }
        return e;
    }

    private String name(String userId) {
        User u = users.get(userId);
        return u == null ? userId : u.name();
    }
}
