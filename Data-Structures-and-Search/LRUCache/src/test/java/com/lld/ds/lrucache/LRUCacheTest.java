package com.lld.ds.lrucache;

import com.lld.ds.lrucache.cache.CacheStats;
import com.lld.ds.lrucache.list.DoublyLinkedList;
import com.lld.ds.lrucache.lru.LRUCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheTest {

    @Test
    void getReturnsStoredValue() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);

        assertEquals(Optional.of(1), cache.get("a"));
        assertEquals(Optional.empty(), cache.get("missing"));
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);                        // a is the oldest → evicted

        assertFalse(cache.containsKey("a"));
        assertEquals(List.of("c", "b"), cache.keysByRecency());
    }

    @Test
    void getRefreshesRecency() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");                           // a becomes most recent
        cache.put("c", 3);                        // so b is evicted

        assertTrue(cache.containsKey("a"));
        assertFalse(cache.containsKey("b"));
    }

    @Test
    void putOnExistingKeyUpdatesAndRefreshesWithoutEvicting() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 10);                       // update, no eviction
        cache.put("c", 3);                        // b is now the LRU

        assertEquals(Optional.of(10), cache.get("a"));
        assertFalse(cache.containsKey("b"));
        assertEquals(1, cache.stats().evictions());
    }

    @Test
    void peekAndContainsDoNotChangeRecencyOrStats() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.peek("a");
        cache.containsKey("a");
        cache.put("c", 3);                        // a is still the LRU → evicted

        assertFalse(cache.containsKey("a"));
        assertEquals(0, cache.stats().requests());
    }

    @Test
    void removeFreesCapacity() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);

        assertTrue(cache.remove("a"));
        assertFalse(cache.remove("a"));
        cache.put("c", 3);                        // room available, nothing evicted

        assertEquals(List.of("c", "b"), cache.keysByRecency());
        assertEquals(0, cache.stats().evictions());
    }

    @Test
    void capacityOne() {
        LRUCache<String, Integer> cache = new LRUCache<>(1);
        cache.put("a", 1);
        cache.put("b", 2);

        assertEquals(1, cache.size());
        assertEquals(Optional.of(2), cache.get("b"));
        assertEquals(Optional.empty(), cache.get("a"));
    }

    @Test
    void clearEmptiesTheCache() {
        LRUCache<String, Integer> cache = new LRUCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.clear();

        assertEquals(0, cache.size());
        cache.put("c", 3);
        assertEquals(List.of("c"), cache.keysByRecency());
    }

    @Test
    void statsAndEvictionListener() {
        List<String> evicted = new ArrayList<>();
        LRUCache<String, Integer> cache = new LRUCache<>(2, (k, v) -> evicted.add(k + "=" + v));
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.get("zzz");
        cache.put("c", 3);
        cache.remove("a");                        // explicit removal is not an eviction

        assertEquals(List.of("b=2"), evicted);
        CacheStats stats = cache.stats();
        assertEquals(1, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(1, stats.evictions());
        assertEquals(0.5, stats.hitRate());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<String, Integer>(0));
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        assertThrows(NullPointerException.class, () -> cache.put(null, 1));
        assertThrows(NullPointerException.class, () -> cache.put("a", null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void toStringShowsOrder() {
        LRUCache<String, Integer> cache = new LRUCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");

        assertEquals("[MRU] a=1 -> b=2 [LRU]", cache.toString());
    }

    /**
     * Cross-check against Java's own LinkedHashMap in access-order mode (a known-correct LRU)
     * over 200 000 random operations, comparing contents AND recency order.
     */
    @Test
    void matchesLinkedHashMapReferenceOnRandomOperations() {
        int capacity = 50;
        LRUCache<Integer, Integer> cache = new LRUCache<>(capacity);
        Map<Integer, Integer> reference = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > capacity;
            }
        };

        Random random = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            int key = random.nextInt(120);
            switch (random.nextInt(10)) {
                case 0, 1, 2, 3 -> {
                    cache.put(key, i);
                    reference.put(key, i);
                }
                case 4 -> assertEquals(reference.remove(key) != null, cache.remove(key));
                default -> assertEquals(Optional.ofNullable(reference.get(key)), cache.get(key));
            }
            assertEquals(reference.size(), cache.size());
        }

        List<Integer> expectedOrder = new ArrayList<>(reference.keySet());   // LRU → MRU
        Collections.reverse(expectedOrder);
        assertEquals(expectedOrder, cache.keysByRecency());
    }

    @Test
    void doublyLinkedListBasics() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        DoublyLinkedList.Node<String> a = list.addFirst("a");
        list.addFirst("b");
        DoublyLinkedList.Node<String> c = list.addFirst("c");   // c b a

        list.moveToFront(a);                                     // a c b
        assertEquals(List.of("a", "c", "b"), list.toList());
        list.remove(c);                                          // a b
        assertEquals("b", list.removeLast().item());
        assertEquals(List.of("a"), list.toList());
        assertEquals(1, list.size());
        list.removeLast();
        assertNull(list.removeLast());
        assertTrue(list.isEmpty());
    }
}
