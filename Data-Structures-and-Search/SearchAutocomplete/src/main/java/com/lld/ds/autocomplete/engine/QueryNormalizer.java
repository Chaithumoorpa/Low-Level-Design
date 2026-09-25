package com.lld.ds.autocomplete.engine;

import java.util.Locale;

/**
 * Makes "  Java   Streams " and "java streams" the same query: trim, lower-case,
 * collapse runs of whitespace. Applied to both stored terms and typed prefixes.
 */
public final class QueryNormalizer {

    private QueryNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** For prefixes, a trailing space is meaningful ("java " ≠ "java"), so keep one. */
    public static String normalizePrefix(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return collapsed.stripLeading();
    }
}
