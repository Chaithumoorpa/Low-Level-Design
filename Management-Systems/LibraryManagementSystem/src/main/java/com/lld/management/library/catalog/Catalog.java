package com.lld.management.library.catalog;

import com.lld.management.library.model.Book;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Searchable list of titles. Title words, author names and subjects each go into an
 * <b>inverted index</b> (word -> ISBNs), so a search intersects a few small sets instead of scanning
 * every book. A query matches when <em>every</em> word in it matches (AND), in any order.
 *
 * <p>Not thread-safe on its own; the library service calls it under its lock.
 */
public final class Catalog {

    private final Map<String, Book> byIsbn = new HashMap<>();
    private final Map<String, Set<String>> titleIndex = new HashMap<>();
    private final Map<String, Set<String>> authorIndex = new HashMap<>();
    private final Map<String, Set<String>> subjectIndex = new HashMap<>();

    public void add(Book book) {
        if (byIsbn.putIfAbsent(book.isbn(), book) != null) {
            throw new IllegalArgumentException("Duplicate ISBN " + book.isbn());
        }
        index(titleIndex, book.title(), book.isbn());
        book.authors().forEach(a -> index(authorIndex, a, book.isbn()));
        index(subjectIndex, book.subject(), book.isbn());
    }

    public Optional<Book> byIsbn(String isbn) {
        return Optional.ofNullable(byIsbn.get(isbn));
    }

    public List<Book> searchTitle(String query) {
        return search(titleIndex, query);
    }

    public List<Book> searchAuthor(String query) {
        return search(authorIndex, query);
    }

    public List<Book> searchSubject(String query) {
        return search(subjectIndex, query);
    }

    /** Any field: a word may match title, author or subject. */
    public List<Book> searchAll(String query) {
        List<String> words = tokens(query);
        if (words.isEmpty()) {
            return List.of();
        }
        Set<String> result = null;
        for (String w : words) {
            Set<String> hits = new HashSet<>();
            hits.addAll(titleIndex.getOrDefault(w, Set.of()));
            hits.addAll(authorIndex.getOrDefault(w, Set.of()));
            hits.addAll(subjectIndex.getOrDefault(w, Set.of()));
            result = intersect(result, hits);
        }
        return sorted(result);
    }

    public Collection<Book> all() {
        return byIsbn.values();
    }

    private List<Book> search(Map<String, Set<String>> index, String query) {
        List<String> words = tokens(query);
        if (words.isEmpty()) {
            return List.of();
        }
        Set<String> result = null;
        for (String w : words) {
            result = intersect(result, index.getOrDefault(w, Set.of()));
        }
        return sorted(result);
    }

    private static Set<String> intersect(Set<String> acc, Set<String> next) {
        if (acc == null) {
            return new HashSet<>(next);
        }
        acc.retainAll(next);
        return acc;
    }

    private List<Book> sorted(Set<String> isbns) {
        return isbns.stream().map(byIsbn::get)
                .sorted(Comparator.comparing(Book::title).thenComparing(Book::isbn)).toList();
    }

    private static void index(Map<String, Set<String>> index, String text, String isbn) {
        for (String t : tokens(text)) {
            index.computeIfAbsent(t, k -> new HashSet<>()).add(isbn);
        }
    }

    /** Lower-case words; punctuation dropped ("Clean Code: A Handbook" -> clean, code, a, handbook). */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
