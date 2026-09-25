package com.lld.ds.autocomplete.engine;

import com.lld.ds.autocomplete.model.Suggestion;
import com.lld.ds.autocomplete.trie.Trie;
import com.lld.ds.autocomplete.trie.TrieNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * State of one search box while the user types.
 *
 * <p>Instead of re-walking the whole prefix on every keystroke, the session remembers the trie node
 * for each typed character. A new character is one child lookup (O(1)), and backspace just drops the
 * last node. If the trie changed meanwhile (another user's search, a removal), the cached path may
 * be stale, so it is rebuilt from the typed text. The trie's version counter tells us when.
 *
 * <p>Typing {@code '#'} submits the query (it is recorded) and clears the box.
 */
public class TypingSession {

    public static final char SUBMIT = '#';

    private final AutocompleteEngine engine;
    private final Trie trie;
    private final StringBuilder typed = new StringBuilder();
    private final List<TrieNode> path = new ArrayList<>();   // path.get(i) = node after i+1 chars (null = off-trie)
    private long seenVersion;

    TypingSession(AutocompleteEngine engine, Trie trie) {
        this.engine = engine;
        this.trie = trie;
        this.seenVersion = trie.version();
    }

    /** Handles one keystroke and returns the suggestions to show (empty after submit). */
    public List<Suggestion> type(char c) {
        if (c == SUBMIT) {
            engine.record(typed.toString());
            reset();
            return List.of();
        }
        char normalized = Character.isWhitespace(c) ? ' ' : Character.toLowerCase(c);
        if (normalized == ' ' && (typed.length() == 0 || typed.charAt(typed.length() - 1) == ' ')) {
            return current();                          // same rule as the normalizer: no leading/double spaces
        }
        syncIfTrieChanged();
        TrieNode last = path.isEmpty() ? trie.root() : path.get(path.size() - 1);
        path.add(last == null ? null : last.child(normalized));
        typed.append(normalized);
        return current();
    }

    /** Types a whole string, returning the suggestions after the last character. */
    public List<Suggestion> type(String text) {
        List<Suggestion> result = current();
        for (char c : text.toCharArray()) {
            result = type(c);
        }
        return result;
    }

    public List<Suggestion> backspace() {
        if (typed.length() > 0) {
            typed.setLength(typed.length() - 1);
            path.remove(path.size() - 1);
        }
        return current();
    }

    public String text() {
        return typed.toString();
    }

    public void reset() {
        typed.setLength(0);
        path.clear();
        seenVersion = trie.version();
    }

    private List<Suggestion> current() {
        if (path.isEmpty()) {
            return List.of();
        }
        syncIfTrieChanged();
        TrieNode node = path.get(path.size() - 1);
        return node == null ? List.of() : node.topSuggestions();
    }

    private void syncIfTrieChanged() {
        if (seenVersion == trie.version()) {
            return;
        }
        path.clear();
        TrieNode node = trie.root();
        for (int i = 0; i < typed.length(); i++) {
            node = node == null ? null : node.child(typed.charAt(i));
            path.add(node);
        }
        seenVersion = trie.version();
    }

    @Override
    public String toString() {
        return "\"" + typed.toString().toLowerCase(Locale.ROOT) + "\"";
    }
}
