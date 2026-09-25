package com.lld.ds.autocomplete.trie;

import com.lld.ds.autocomplete.model.Suggestion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One character position in the trie.
 *
 * <p>Besides children and the (optional) term that ends here, every node caches the top-K
 * suggestions of its whole subtree. That cache is what turns a query into "walk the prefix,
 * read a list" instead of "walk the prefix, then search the entire subtree".
 */
public final class TrieNode {

    final Map<Character, TrieNode> children = new HashMap<>();
    final TrieNode parent;
    final char ch;

    String term;              // non-null if a stored query ends at this node
    long frequency;
    long lastUsed;
    List<Suggestion> top = List.of();

    TrieNode(TrieNode parent, char ch) {
        this.parent = parent;
        this.ch = ch;
    }

    public TrieNode child(char c) {
        return children.get(c);
    }

    /** Pre-computed best suggestions for every query that starts with this node's prefix. */
    public List<Suggestion> topSuggestions() {
        return top;
    }

    boolean isTerminal() {
        return term != null;
    }
}
