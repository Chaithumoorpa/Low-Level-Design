package com.lld.ds.autocomplete.ranking;

import com.lld.ds.autocomplete.model.Suggestion;

import java.util.Comparator;

/**
 * Strategy pattern: how suggestions are ordered. Best first.
 *
 * <p>Constraint that makes pre-computed top-K lists possible: a suggestion's rank may depend only on
 * its OWN data (frequency, last use, text). Recording one term then changes that term's position
 * and nothing else, so only the nodes on its path need refreshing.
 */
public enum RankingStrategy {

    /** Most searched first; ties broken alphabetically (deterministic, as most specs require). */
    BY_FREQUENCY(Comparator.comparingLong(Suggestion::frequency).reversed()
            .thenComparing(Suggestion::term)),

    /** Most recently searched first ("recent searches" style). */
    BY_RECENCY(Comparator.comparingLong(Suggestion::lastUsed).reversed()
            .thenComparing(Suggestion::term));

    private final Comparator<Suggestion> order;

    RankingStrategy(Comparator<Suggestion> order) {
        this.order = order;
    }

    public Comparator<Suggestion> comparator() {
        return order;
    }
}
