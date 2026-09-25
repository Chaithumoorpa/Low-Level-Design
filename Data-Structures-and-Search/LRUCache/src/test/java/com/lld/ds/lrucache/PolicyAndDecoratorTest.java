package com.lld.ds.lrucache;

import com.lld.ds.lrucache.cache.Cache;
import com.lld.ds.lrucache.decorator.SynchronizedCache;
import com.lld.ds.lrucache.decorator.TtlCache;
import com.lld.ds.lrucache.lru.LRUCache;
import com.lld.ds.lrucache.policy.LFUEvictionPolicy;
import com.lld.ds.lrucache.policy.PolicyCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PolicyAndDecoratorTest {

    // ------------------------------------------------------------------ eviction policies

    @Test
    void lruPolicyBehavesExactlyLikeLruCache() {
        LRUCache<Integer, Integer> classic = new LRUCache<>(20);
        PolicyCache<Integer, Integer> pluggable = PolicyCache.lru(20);
        Random random = new Random(7);

        for (int i = 0; i < 50_000; i++) {
            int key = random.nextInt(60);
            if (random.nextBoolean()) {
                classic.put(key, i);
                pluggable.put(key, i);
            } else {
                assertEquals(classic.get(key), pluggable.get(key));
            }
        }
        assertEquals(classic.stats(), pluggable.stats());
    }

    @Test
    void lfuEvictsLeastFrequentlyUsed() {
        PolicyCache<String, Integer> cache = PolicyCache.lfu(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.get("a");                           // a: 3 uses, b: 1 use
        cache.put("c", 3);                        // evicts b

        assertTrue(cache.containsKey("a"));
        assertFalse(cache.containsKey("b"));
    }

    @Test
    void lfuBreaksTiesByRecency() {
        PolicyCache<String, Integer> cache = PolicyCache.lfu(2);
        cache.put("a", 1);
        cache.put("b", 2);                        // both used once; a is older
        cache.put("c", 3);

        assertFalse(cache.containsKey("a"));
        assertTrue(cache.containsKey("b"));
    }

    @Test
    void lfuNewKeyIsEvictedBeforeFrequentOnes() {
        PolicyCache<String, Integer> cache = PolicyCache.lfu(2);
        cache.put("hot", 1);
        for (int i = 0; i < 5; i++) {
            cache.get("hot");
        }
        cache.put("x", 2);
        cache.put("y", 3);                        // x (1 use) goes, hot (6 uses) stays

        assertTrue(cache.containsKey("hot"));
        assertFalse(cache.containsKey("x"));
    }

    @Test
    void lfuHandlesRemovalOfTheLeastFrequentKey() {
        LFUEvictionPolicy<String> policy = new LFUEvictionPolicy<>();
        PolicyCache<String, Integer> cache = new PolicyCache<>(2, policy);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("b");
        cache.get("b");                           // a:1, b:3
        cache.remove("a");                        // minFreq bucket is now empty
        cache.put("c", 3);                        // c:1
        cache.get("c");                           // c:2
        cache.put("d", 4);                        // evicts c (2) rather than b (3)

        assertTrue(cache.containsKey("b"));
        assertFalse(cache.containsKey("c"));
        assertEquals(3, policy.frequencyOf("b"));
    }

    @Test
    void fifoIgnoresReads() {
        PolicyCache<String, Integer> cache = PolicyCache.fifo(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");                           // would save a under LRU, not under FIFO
        cache.put("c", 3);

        assertFalse(cache.containsKey("a"));
        assertTrue(cache.containsKey("b"));
    }

    @Test
    void policyCacheReportsEvictions() {
        List<String> evicted = new ArrayList<>();
        PolicyCache<String, Integer> cache = new PolicyCache<>(1, new LFUEvictionPolicy<>(),
                (k, v) -> evicted.add(k));
        cache.put("a", 1);
        cache.put("b", 2);

        assertEquals(List.of("a"), evicted);
        assertEquals(1, cache.stats().evictions());
    }

    // ------------------------------------------------------------------ TTL decorator

    /** A clock the test can move forward, so expiry is tested without sleeping. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void ttlEntriesExpire() {
        MutableClock clock = new MutableClock();
        TtlCache<String, String> cache = new TtlCache<>(new LRUCache<>(10), Duration.ofSeconds(30), clock);
        cache.put("session", "alice");

        clock.advance(Duration.ofSeconds(29));
        assertEquals(Optional.of("alice"), cache.get("session"));

        clock.advance(Duration.ofSeconds(1));
        assertFalse(cache.containsKey("session"));
        assertEquals(Optional.empty(), cache.get("session"));
        assertEquals(0, cache.size());              // dropped on read
        assertEquals(1, cache.stats().misses());    // expired read counted as a miss
    }

    @Test
    void ttlPutRestartsTheTimer() {
        MutableClock clock = new MutableClock();
        TtlCache<String, String> cache = new TtlCache<>(new LRUCache<>(10), Duration.ofSeconds(30), clock);
        cache.put("k", "v1");
        clock.advance(Duration.ofSeconds(20));
        cache.put("k", "v2");
        clock.advance(Duration.ofSeconds(20));

        assertEquals(Optional.of("v2"), cache.get("k"));
    }

    @Test
    void ttlWorksOnTopOfLfuToo() {
        MutableClock clock = new MutableClock();
        TtlCache<String, Integer> cache = new TtlCache<>(PolicyCache.lfu(2), Duration.ofMinutes(1), clock);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);                          // inner LFU evicts a; its expiry data goes with it

        assertEquals(2, cache.size());
        assertFalse(cache.containsKey("a"));
        assertThrows(IllegalArgumentException.class,
                () -> new TtlCache<String, Integer>(PolicyCache.lru(1), Duration.ZERO, clock));
    }

    // ------------------------------------------------------------------ thread safety

    @Test
    void synchronizedCacheSurvivesConcurrentUse() throws Exception {
        Cache<Integer, Integer> cache = new SynchronizedCache<>(new LRUCache<>(100));
        int threads = 8;
        int opsPerThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int seed = t;
            futures.add(pool.submit(() -> {
                Random random = new Random(seed);
                start.await();
                for (int i = 0; i < opsPerThread; i++) {
                    int key = random.nextInt(300);
                    switch (random.nextInt(3)) {
                        case 0 -> cache.put(key, i);
                        case 1 -> cache.get(key);
                        default -> cache.remove(key);
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);            // rethrows any exception from a worker
        }
        pool.shutdown();

        assertTrue(cache.size() <= 100);
        long gets = cache.stats().requests();
        assertTrue(gets > 0);
        // Internal consistency: every key the cache claims to hold can actually be read back.
        int present = 0;
        for (int k = 0; k < 300; k++) {
            if (cache.containsKey(k)) {
                present++;
                assertTrue(cache.peek(k).isPresent());
            }
        }
        assertEquals(cache.size(), present);
    }
}
