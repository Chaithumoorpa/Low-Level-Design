package com.lld.ds.lfucache;

import com.lld.ds.lfucache.cache.Cache;
import com.lld.ds.lfucache.cache.CacheStats;
import com.lld.ds.lfucache.lfu.LFUCache;
import com.lld.ds.lfucache.tree.TreeLFUCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class LFUCacheTest {

    // ------------------------------------------------------------------ behaviour (both implementations)

    /** Every behavioural test runs against both the O(1) and the O(log n) implementation. */
    private static final List<Function<Integer, Cache<String, Integer>>> IMPLEMENTATIONS = List.of(
            LFUCache::new,
            TreeLFUCache::new);

    private static void forEachImplementation(java.util.function.Consumer<Function<Integer, Cache<String, Integer>>> test) {
        IMPLEMENTATIONS.forEach(test);
    }

    @Test
    void evictsLeastFrequentlyUsed() {
        forEachImplementation(make -> {
            Cache<String, Integer> cache = make.apply(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.get("a");                        // a:2, b:1
            cache.put("c", 3);                     // evicts b

            assertTrue(cache.containsKey("a"));
            assertFalse(cache.containsKey("b"));
            assertTrue(cache.containsKey("c"));
        });
    }

    @Test
    void tieBrokenByLeastRecentlyUsed() {
        forEachImplementation(make -> {
            Cache<String, Integer> cache = make.apply(3);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.put("c", 3);
            cache.get("a");
            cache.get("b");                        // a:2, b:2, c:1
            cache.get("c");                        // all 2; a is the least recent of them
            cache.put("d", 4);

            assertFalse(cache.containsKey("a"));
        });
    }

    @Test
    void updatingAValueCountsAsAUse() {
        forEachImplementation(make -> {
            Cache<String, Integer> cache = make.apply(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.put("a", 10);                    // a:2
            cache.put("c", 3);                     // evicts b

            assertEquals(Optional.of(10), cache.get("a"));
            assertFalse(cache.containsKey("b"));
        });
    }

    @Test
    void newKeyIsTheFirstCandidateForEviction() {
        forEachImplementation(make -> {
            Cache<String, Integer> cache = make.apply(2);
            cache.put("hot", 1);
            for (int i = 0; i < 10; i++) {
                cache.get("hot");
            }
            cache.put("x", 2);
            cache.put("y", 3);                     // x (freq 1) goes, hot stays

            assertTrue(cache.containsKey("hot"));
            assertFalse(cache.containsKey("x"));
            assertTrue(cache.containsKey("y"));
        });
    }

    @Test
    void removingTheOnlyLowFrequencyKeyThenEvicting() {
        forEachImplementation(make -> {
            Cache<String, Integer> cache = make.apply(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.get("b");
            cache.get("b");                        // a:1, b:3
            cache.remove("a");                     // lowest bucket disappears
            cache.put("c", 3);                     // c:1
            cache.get("c");                        // c:2
            cache.put("d", 4);                     // evicts c (2), not b (3)

            assertTrue(cache.containsKey("b"));
            assertFalse(cache.containsKey("c"));
            assertTrue(cache.containsKey("d"));
        });
    }

    @Test
    void capacityOneAlwaysKeepsTheNewest() {
        forEachImplementation(make -> {
            Cache<String, Integer> cache = make.apply(1);
            cache.put("a", 1);
            cache.get("a");
            cache.get("a");
            cache.put("b", 2);                     // must still make room, even for a popular a

            assertEquals(1, cache.size());
            assertTrue(cache.containsKey("b"));
        });
    }

    @Test
    void peekDoesNotCountAsUse() {
        forEachImplementation(make -> {
            Cache<String, Integer> cache = make.apply(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.get("b");                        // b:2
            cache.peek("a");
            cache.peek("a");                       // a stays at 1
            cache.put("c", 3);

            assertFalse(cache.containsKey("a"));
            assertEquals(1, cache.stats().requests());
        });
    }

    @Test
    void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new LFUCache<String, Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new TreeLFUCache<String, Integer>(0));
        assertThrows(IllegalArgumentException.class,
                () -> LFUCache.<String, Integer>builder().capacity(2).agingEvery(-1).build());
        forEachImplementation(make -> {
            Cache<String, Integer> cache = make.apply(2);
            assertThrows(NullPointerException.class, () -> cache.put(null, 1));
            assertThrows(NullPointerException.class, () -> cache.put("a", null));
            assertThrows(NullPointerException.class, () -> cache.get(null));
        });
    }

    // ------------------------------------------------------------------ O(1) implementation details

    @Test
    void frequencyBucketsShowTheStructure() {
        LFUCache<String, Integer> cache = new LFUCache<>(4);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("a");
        cache.get("a");
        cache.get("b");

        Map<Integer, List<String>> expected = new LinkedHashMap<>();
        expected.put(1, List.of("c"));
        expected.put(2, List.of("b"));
        expected.put(3, List.of("a"));
        assertEquals(expected, cache.frequencyBuckets());
        assertEquals(3, cache.frequencyOf("a"));
        assertEquals(0, cache.frequencyOf("zzz"));
        assertEquals(Optional.of("c"), cache.nextVictim());
        assertEquals("f=1 [c]  f=2 [b]  f=3 [a]", cache.toString());
    }

    @Test
    void emptyFrequencyNodesAreRemoved() {
        LFUCache<String, Integer> cache = new LFUCache<>(4);
        cache.put("a", 1);
        cache.get("a");
        cache.get("a");                            // bucket 1 and 2 must be gone

        assertEquals(List.of(3), new ArrayList<>(cache.frequencyBuckets().keySet()));
        cache.remove("a");
        assertTrue(cache.frequencyBuckets().isEmpty());
        assertEquals(Optional.empty(), cache.nextVictim());
    }

    @Test
    void statsAndEvictionListener() {
        List<String> evicted = new ArrayList<>();
        LFUCache<String, Integer> cache = LFUCache.<String, Integer>builder()
                .capacity(2)
                .evictionListener((k, v) -> evicted.add(k + "=" + v))
                .build();
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.get("nope");
        cache.put("c", 3);
        cache.remove("a");                         // explicit remove is not an eviction

        assertEquals(List.of("b=2"), evicted);
        assertEquals(new CacheStats(1, 1, 1), cache.stats());
    }

    @Test
    void clearResetsEverything() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);
        cache.put("a", 1);
        cache.get("a");
        cache.clear();

        assertEquals(0, cache.size());
        cache.put("b", 2);
        assertEquals(1, cache.frequencyOf("b"));
    }

    // ------------------------------------------------------------------ aging

    @Test
    void halvingKeepsRankingAndMinimumOfOne() {
        LFUCache<String, Integer> cache = new LFUCache<>(3);
        cache.put("a", 1);                         // a:1
        cache.put("b", 2);
        cache.get("b");
        cache.get("b");
        cache.get("b");                            // b:4
        cache.put("c", 3);
        for (int i = 0; i < 9; i++) {
            cache.get("c");                        // c:10
        }

        cache.halveFrequencies();

        assertEquals(1, cache.frequencyOf("a"));
        assertEquals(2, cache.frequencyOf("b"));
        assertEquals(5, cache.frequencyOf("c"));
    }

    @Test
    void halvingMergesBucketsWithLowerOriginalFrequencyEvictedFirst() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);
        cache.put("a", 1);                         // a:1
        cache.put("b", 2);
        cache.get("b");                            // b:2

        cache.halveFrequencies();                  // both become 1; a (was 1) is the older one

        assertEquals(Optional.of("a"), cache.nextVictim());
    }

    /**
     * The classic LFU problem: a key that was hot in the past never leaves, even after the workload
     * moves on. Aging fixes it.
     */
    @Test
    void agingLetsOldPopularityFade() {
        LFUCache<String, Integer> plain = new LFUCache<>(2);
        LFUCache<String, Integer> aging = LFUCache.<String, Integer>builder().capacity(2).agingEvery(20).build();

        for (LFUCache<String, Integer> cache : List.of(plain, aging)) {
            cache.put("yesterday", 0);
            for (int i = 0; i < 100; i++) {
                cache.get("yesterday");            // very popular in the past
            }
            for (int round = 0; round < 60; round++) {       // new workload: today1 / today2 alternate
                String key = "today" + (round % 2);
                if (cache.get(key).isEmpty()) {
                    cache.put(key, round);
                }
                cache.get(key);
            }
        }

        assertTrue(plain.containsKey("yesterday"), "without aging the stale key squats forever");
        assertFalse(aging.containsKey("yesterday"), "with aging it is eventually evicted");
    }

    // ------------------------------------------------------------------ randomized cross-check

    /**
     * Brute-force oracle, written independently: scan every entry for the lowest
     * (frequency, lastUsed). O(n) per eviction but obviously correct.
     */
    private static final class NaiveLfu {
        private final int capacity;
        private final Map<Integer, int[]> entries = new HashMap<>();   // key -> {value, freq, lastUsed}
        private int clock;
        final List<Integer> evicted = new ArrayList<>();

        NaiveLfu(int capacity) {
            this.capacity = capacity;
        }

        Optional<Integer> get(int key) {
            int[] e = entries.get(key);
            if (e == null) {
                return Optional.empty();
            }
            e[1]++;
            e[2] = ++clock;
            return Optional.of(e[0]);
        }

        void put(int key, int value) {
            int[] e = entries.get(key);
            if (e != null) {
                e[0] = value;
                e[1]++;
                e[2] = ++clock;
                return;
            }
            if (entries.size() == capacity) {
                int victim = -1;
                int[] best = null;
                for (Map.Entry<Integer, int[]> candidate : entries.entrySet()) {
                    int[] c = candidate.getValue();
                    if (best == null || c[1] < best[1] || (c[1] == best[1] && c[2] < best[2])) {
                        best = c;
                        victim = candidate.getKey();
                    }
                }
                entries.remove(victim);
                evicted.add(victim);
            }
            entries.put(key, new int[]{value, 1, ++clock});
        }

        boolean remove(int key) {
            return entries.remove(key) != null;
        }
    }

    @ParameterizedTest(name = "capacity {0}")
    @ValueSource(ints = {1, 2, 5, 50})
    void bothImplementationsMatchTheBruteForceOracle(int capacity) {
        NaiveLfu oracle = new NaiveLfu(capacity);
        List<Integer> fastEvicted = new ArrayList<>();
        List<Integer> treeEvicted = new ArrayList<>();
        LFUCache<Integer, Integer> fast = LFUCache.<Integer, Integer>builder()
                .capacity(capacity).evictionListener((k, v) -> fastEvicted.add(k)).build();
        TreeLFUCache<Integer, Integer> tree = new TreeLFUCache<>(capacity, (k, v) -> treeEvicted.add(k));

        Random random = new Random(capacity * 31L);
        for (int i = 0; i < 100_000; i++) {
            int key = random.nextInt(capacity * 3);
            switch (random.nextInt(10)) {
                case 0, 1, 2, 3 -> {
                    oracle.put(key, i);
                    fast.put(key, i);
                    tree.put(key, i);
                }
                case 4 -> {
                    boolean expected = oracle.remove(key);
                    assertEquals(expected, fast.remove(key));
                    assertEquals(expected, tree.remove(key));
                }
                default -> {
                    Optional<Integer> expected = oracle.get(key);
                    assertEquals(expected, fast.get(key), "O(1) get at op " + i);
                    assertEquals(expected, tree.get(key), "tree get at op " + i);
                }
            }
            assertEquals(oracle.entries.size(), fast.size());
        }
        assertEquals(oracle.evicted, fastEvicted, "O(1) cache evicted different keys");
        assertEquals(oracle.evicted, treeEvicted, "tree cache evicted different keys");
        for (int key : oracle.entries.keySet()) {
            assertEquals(oracle.entries.get(key)[1], fast.frequencyOf(key));
            assertEquals(oracle.entries.get(key)[1], tree.frequencyOf(key));
        }
    }
}
