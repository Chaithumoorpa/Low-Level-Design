package com.lld.ds.autocomplete.engine;

import com.lld.ds.autocomplete.model.Suggestion;
import com.lld.ds.autocomplete.ranking.RankingStrategy;
import com.lld.ds.autocomplete.trie.Trie;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facade over the whole system: record searches, get suggestions, remove or block terms,
 * and open per-user typing sessions.
 */
public class AutocompleteEngine {

    private final Trie trie;
    private final RankingStrategy ranking;
    private final int k;
    private final Set<String> blockedWords = new HashSet<>();
    private long clock;

    public AutocompleteEngine() {
        this(3, RankingStrategy.BY_FREQUENCY);
    }

    public AutocompleteEngine(int k, RankingStrategy ranking) {
        this.k = k;
        this.ranking = ranking;
        this.trie = new Trie(k, ranking.comparator());
    }

    // ------------------------------------------------------------------ writes

    /**
     * A user searched for {@code query}. Returns false if it was ignored (empty or blocked).
     */
    public boolean record(String query) {
        return record(query, 1);
    }

    /** Bulk load / replay: adds {@code count} searches at once. */
    public boolean record(String query, long count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
        String term = QueryNormalizer.normalize(query);
        if (term.isEmpty() || isBlocked(term)) {
            return false;
        }
        trie.add(term, count, ++clock);
        return true;
    }

    public void loadAll(Map<String, Long> counts) {
        counts.forEach(this::record);
    }

    public boolean remove(String query) {
        return trie.remove(QueryNormalizer.normalize(query));
    }

    /**
     * Blocks a word: existing queries containing it are deleted, and future ones are ignored.
     * O(n) over stored terms, which is fine for a rare admin action.
     */
    public int blockWord(String word) {
        String w = QueryNormalizer.normalize(word);
        if (w.isEmpty() || w.contains(" ")) {
            throw new IllegalArgumentException("Block single words, got \"" + word + "\"");
        }
        blockedWords.add(w);
        int removed = 0;
        for (String term : trie.allTerms()) {
            if (isBlocked(term)) {
                trie.remove(term);
                removed++;
            }
        }
        return removed;
    }

    private boolean isBlocked(String term) {
        if (blockedWords.isEmpty()) {
            return false;
        }
        for (String token : term.split(" ")) {
            if (blockedWords.contains(token)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ reads

    /** Top-K completions for what the user has typed so far. Empty prefix → no suggestions. */
    public List<Suggestion> suggest(String prefix) {
        String p = QueryNormalizer.normalizePrefix(prefix);
        return p.isEmpty() ? List.of() : trie.topK(p);
    }

    /** Starts a keystroke-by-keystroke session (one per user / search box). */
    public TypingSession newSession() {
        return new TypingSession(this, trie);
    }

    public long frequencyOf(String query) {
        return trie.frequencyOf(QueryNormalizer.normalize(query));
    }

    public int termCount() {
        return trie.termCount();
    }

    public int nodeCount() {
        return trie.nodeCount();
    }

    public int k() {
        return k;
    }

    public RankingStrategy ranking() {
        return ranking;
    }

    /** Exposed for tests: the unoptimised answer for the same prefix. */
    List<Suggestion> suggestBruteForce(String prefix) {
        String p = QueryNormalizer.normalizePrefix(prefix);
        return p.isEmpty() ? List.of() : trie.topKBruteForce(p);
    }
}
