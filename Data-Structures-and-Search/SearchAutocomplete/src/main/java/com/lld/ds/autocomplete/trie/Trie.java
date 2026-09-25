package com.lld.ds.autocomplete.trie;

import com.lld.ds.autocomplete.model.Suggestion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Prefix tree whose nodes cache their subtree's top-K suggestions.
 *
 * <ul>
 *   <li><b>Query</b>: walk the prefix (O(L)) and return the cached list (O(K)).</li>
 *   <li><b>Update</b>: after a term's count changes, refresh the caches on the path from its node
 *       up to the root. Each node merges its own term with its children's top-K lists:
 *       O(L x children x K).</li>
 * </ul>
 * Reads are far more frequent than writes in autocomplete (every keystroke is a read), so paying
 * on writes to make reads cheap is the right trade.
 */
public class Trie {

    private final int k;
    private final Comparator<Suggestion> order;
    private final TrieNode root = new TrieNode(null, '\0');
    private int termCount;
    private int nodeCount = 1;
    private long version;             // bumped on every structural or ranking change

    public Trie(int k, Comparator<Suggestion> order) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1, got " + k);
        }
        this.k = k;
        this.order = order;
    }

    /** Adds {@code delta} searches to {@code term} (creating it if new) and stamps its last use. */
    public void add(String term, long delta, long tick) {
        TrieNode node = root;
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            TrieNode next = node.children.get(c);
            if (next == null) {
                next = new TrieNode(node, c);
                node.children.put(c, next);
                nodeCount++;
            }
            node = next;
        }
        if (!node.isTerminal()) {
            node.term = term;
            termCount++;
        }
        node.frequency += delta;
        node.lastUsed = tick;
        refreshUpFrom(node);
    }

    /** Deletes a term, prunes branches that no longer lead anywhere, and repairs the caches. */
    public boolean remove(String term) {
        TrieNode node = find(term);
        if (node == null || !node.isTerminal()) {
            return false;
        }
        node.term = null;
        node.frequency = 0;
        node.lastUsed = 0;
        termCount--;

        while (node != root && !node.isTerminal() && node.children.isEmpty()) {
            TrieNode parent = node.parent;
            parent.children.remove(node.ch);
            nodeCount--;
            node = parent;
        }
        refreshUpFrom(node);
        return true;
    }

    /** Node for a prefix, or null if no stored term starts with it. */
    public TrieNode find(String prefix) {
        TrieNode node = root;
        for (int i = 0; i < prefix.length() && node != null; i++) {
            node = node.children.get(prefix.charAt(i));
        }
        return node;
    }

    /** Fast path: cached top-K for the prefix. */
    public List<Suggestion> topK(String prefix) {
        TrieNode node = find(prefix);
        return node == null ? List.of() : node.top;
    }

    /**
     * Slow path, kept on purpose: collect EVERY term under the prefix, sort, take K.
     * The obvious first solution, and the reference the tests compare the cache against.
     */
    public List<Suggestion> topKBruteForce(String prefix) {
        TrieNode start = find(prefix);
        if (start == null) {
            return List.of();
        }
        List<Suggestion> all = new ArrayList<>();
        Deque<TrieNode> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            TrieNode n = stack.pop();
            if (n.isTerminal()) {
                all.add(new Suggestion(n.term, n.frequency, n.lastUsed));
            }
            n.children.values().forEach(stack::push);
        }
        all.sort(order);
        return List.copyOf(all.subList(0, Math.min(k, all.size())));
    }

    /** Every stored term (any order). O(n); for admin tasks such as applying a block list. */
    public List<String> allTerms() {
        List<String> terms = new ArrayList<>(termCount);
        Deque<TrieNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            TrieNode n = stack.pop();
            if (n.isTerminal()) {
                terms.add(n.term);
            }
            n.children.values().forEach(stack::push);
        }
        return terms;
    }

    public long frequencyOf(String term) {
        TrieNode node = find(term);
        return node == null ? 0 : node.frequency;
    }

    public TrieNode root() {
        return root;
    }

    public int termCount() {
        return termCount;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public long version() {
        return version;
    }

    // ------------------------------------------------------------------ cache maintenance

    private void refreshUpFrom(TrieNode node) {
        for (TrieNode n = node; n != null; n = n.parent) {
            recompute(n);
        }
        version++;
    }

    /** top(n) = best K of { n's own term } ∪ top(child) for every child. */
    private void recompute(TrieNode n) {
        List<Suggestion> candidates = new ArrayList<>(k * (n.children.size() + 1));
        if (n.isTerminal()) {
            candidates.add(new Suggestion(n.term, n.frequency, n.lastUsed));
        }
        for (TrieNode child : n.children.values()) {
            candidates.addAll(child.top);
        }
        candidates.sort(order);
        n.top = List.copyOf(candidates.subList(0, Math.min(k, candidates.size())));
    }
}
