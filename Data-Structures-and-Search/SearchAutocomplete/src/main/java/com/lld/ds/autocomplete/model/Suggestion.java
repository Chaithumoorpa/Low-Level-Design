package com.lld.ds.autocomplete.model;

/**
 * One ranked completion.
 *
 * @param term      the full stored query, e.g. "java streams"
 * @param frequency how many times it was searched
 * @param lastUsed  logical time of the most recent search (higher = more recent)
 */
public record Suggestion(String term, long frequency, long lastUsed) {

    @Override
    public String toString() {
        return term + " (" + frequency + ")";
    }
}
