package com.lld.ds.autocomplete.engine;

import com.lld.ds.autocomplete.model.Suggestion;
import com.lld.ds.autocomplete.ranking.RankingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AutocompleteEngineTest {

    private static List<String> terms(List<Suggestion> suggestions) {
        return suggestions.stream().map(Suggestion::term).toList();
    }

    private static AutocompleteEngine sample() {
        AutocompleteEngine engine = new AutocompleteEngine(3, RankingStrategy.BY_FREQUENCY);
        engine.loadAll(Map.of(
                "java", 50L,
                "javascript", 80L,
                "java streams", 30L,
                "java interview questions", 45L,
                "jar file", 12L,
                "python", 90L));
        return engine;
    }

    // ------------------------------------------------------------------ ranking

    @Test
    void returnsTopKByFrequency() {
        AutocompleteEngine engine = sample();

        assertEquals(List.of("javascript", "java", "java interview questions"), terms(engine.suggest("ja")));
        assertEquals(List.of("java interview questions", "java streams"), terms(engine.suggest("java ")));  // "java" itself lacks the space
        assertEquals(List.of("python"), terms(engine.suggest("py")));
    }

    @Test
    void tiesAreBrokenAlphabetically() {
        AutocompleteEngine engine = new AutocompleteEngine(2, RankingStrategy.BY_FREQUENCY);
        engine.record("banana", 5);
        engine.record("band", 5);
        engine.record("ban", 5);

        assertEquals(List.of("ban", "banana"), terms(engine.suggest("ba")));
    }

    @Test
    void prefixItselfCanBeASuggestion() {
        AutocompleteEngine engine = sample();

        assertTrue(terms(engine.suggest("java")).contains("java"));
    }

    @Test
    void unknownOrEmptyPrefixGivesNothing() {
        AutocompleteEngine engine = sample();

        assertTrue(engine.suggest("xyz").isEmpty());
        assertTrue(engine.suggest("").isEmpty());
        assertTrue(engine.suggest("   ").isEmpty());
    }

    @Test
    void recordingChangesTheRanking() {
        AutocompleteEngine engine = sample();
        engine.record("jar file", 40);                 // 12 → 52: now above "java" (50)

        assertEquals(List.of("javascript", "jar file", "java"), terms(engine.suggest("ja")));
        assertEquals(52, engine.frequencyOf("jar file"));
    }

    @Test
    void newTermAppearsImmediately() {
        AutocompleteEngine engine = sample();
        engine.record("jaguar", 1000);

        assertEquals("jaguar", engine.suggest("j").get(0).term());
    }

    @Test
    void recencyRanking() {
        AutocompleteEngine engine = new AutocompleteEngine(3, RankingStrategy.BY_RECENCY);
        engine.record("cat", 100);
        engine.record("car");
        engine.record("cab");
        engine.record("cat");                          // searched last → first

        assertEquals(List.of("cat", "cab", "car"), terms(engine.suggest("ca")));
    }

    // ------------------------------------------------------------------ normalization

    @Test
    void queriesAreNormalized() {
        AutocompleteEngine engine = new AutocompleteEngine();
        engine.record("  Java   Streams ");
        engine.record("java streams");

        assertEquals(2, engine.frequencyOf("JAVA STREAMS"));
        assertEquals(1, engine.termCount());
        assertEquals(List.of("java streams"), terms(engine.suggest("JAVA  s")));
    }

    @Test
    void emptyQueriesAreIgnored() {
        AutocompleteEngine engine = new AutocompleteEngine();

        assertFalse(engine.record("   "));
        assertEquals(0, engine.termCount());
        assertThrows(IllegalArgumentException.class, () -> engine.record("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new AutocompleteEngine(0, RankingStrategy.BY_FREQUENCY));
    }

    // ------------------------------------------------------------------ removal and blocking

    @Test
    void removeUpdatesSuggestionsAndPrunesNodes() {
        AutocompleteEngine engine = new AutocompleteEngine();
        engine.record("cart", 5);
        int nodesBefore = engine.nodeCount();
        engine.record("cartoon", 10);

        assertTrue(engine.remove("cartoon"));
        assertFalse(engine.remove("cartoon"));
        assertEquals(List.of("cart"), terms(engine.suggest("car")));
        assertEquals(nodesBefore, engine.nodeCount(), "the \"oon\" branch must be pruned");
    }

    @Test
    void removingAPrefixTermKeepsLongerTerms() {
        AutocompleteEngine engine = new AutocompleteEngine();
        engine.record("car", 9);
        engine.record("cart", 5);

        engine.remove("car");

        assertEquals(List.of("cart"), terms(engine.suggest("ca")));
    }

    @Test
    void blockedWordsAreRemovedAndCannotComeBack() {
        AutocompleteEngine engine = sample();
        engine.record("java scam deals", 500);

        assertEquals(1, engine.blockWord("scam"));
        assertFalse(terms(engine.suggest("java")).contains("java scam deals"));
        assertFalse(engine.record("cheap scam"));
        assertTrue(engine.record("scampi recipe"), "whole words only, not substrings");
        assertThrows(IllegalArgumentException.class, () -> engine.blockWord("two words"));
    }

    // ------------------------------------------------------------------ typing session

    @Test
    void sessionSuggestsAfterEachKeystroke() {
        AutocompleteEngine engine = sample();
        TypingSession session = engine.newSession();

        assertEquals(List.of("javascript", "java", "java interview questions"), terms(session.type('j')));
        assertEquals(List.of("javascript", "java", "java interview questions"), terms(session.type('a')));
        assertEquals(List.of("jar file"), terms(session.type('r')));
        assertTrue(session.type('x').isEmpty());                 // "jarx" matches nothing
        assertEquals(List.of("jar file"), terms(session.backspace()));
        assertEquals("jar", session.text());
    }

    @Test
    void sessionSubmitRecordsTheQuery() {
        AutocompleteEngine engine = sample();
        TypingSession session = engine.newSession();
        session.type("Rust lang");

        assertTrue(session.type(TypingSession.SUBMIT).isEmpty());
        assertEquals("", session.text());
        assertEquals(1, engine.frequencyOf("rust lang"));
        assertEquals(List.of("rust lang"), terms(engine.newSession().type("ru")));
    }

    @Test
    void sessionSkipsLeadingAndDoubleSpacesLikeTheNormalizer() {
        AutocompleteEngine engine = sample();
        TypingSession session = engine.newSession();

        session.type("  java  s");

        assertEquals("java s", session.text());
        assertEquals(List.of("java streams"), terms(session.type('t')));
    }

    @Test
    void sessionSeesChangesMadeWhileTyping() {
        AutocompleteEngine engine = sample();
        TypingSession session = engine.newSession();
        session.type("jav");

        engine.remove("javascript");                   // another admin/user changes the data
        engine.record("javelin", 1000);

        assertEquals("javelin", session.type('e').get(0).term());
        assertEquals(List.of("javelin", "java", "java interview questions"), terms(session.backspace()));
    }

    // ------------------------------------------------------------------ cache correctness

    /**
     * The pre-computed top-K lists must always equal the brute-force answer (collect every term under
     * the prefix, sort, cut), after any mix of records and removals, for both ranking strategies.
     */
    @ParameterizedTest
    @EnumSource(RankingStrategy.class)
    void cachedSuggestionsAlwaysMatchBruteForce(RankingStrategy ranking) {
        AutocompleteEngine engine = new AutocompleteEngine(5, ranking);
        Random random = new Random(ranking.ordinal() + 1);
        String alphabet = "abc ";
        List<String> vocabulary = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            StringBuilder word = new StringBuilder();
            int len = 1 + random.nextInt(6);
            for (int j = 0; j < len; j++) {
                word.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            vocabulary.add(word.toString());
        }

        for (int op = 0; op < 20_000; op++) {
            String term = vocabulary.get(random.nextInt(vocabulary.size()));
            if (random.nextInt(5) == 0) {
                engine.remove(term);
            } else {
                engine.record(term, 1 + random.nextInt(3));
            }
            if (op % 50 == 0) {
                for (String prefix : List.of("a", "ab", "b", "c", "ca", "ba", "abc", "a b", "cc")) {
                    assertEquals(engine.suggestBruteForce(prefix), engine.suggest(prefix),
                            "prefix \"" + prefix + "\" after op " + op);
                }
            }
        }
    }
}
