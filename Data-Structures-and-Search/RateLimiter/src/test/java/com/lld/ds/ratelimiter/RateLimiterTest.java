package com.lld.ds.ratelimiter;

import com.lld.ds.ratelimiter.algorithm.Algorithm;
import com.lld.ds.ratelimiter.algorithm.KeyedRateLimiter;
import com.lld.ds.ratelimiter.composite.CompositeRateLimiter;
import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.ManualTimeSource;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.RateLimiter;
import com.lld.ds.ratelimiter.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private final ManualTimeSource clock = new ManualTimeSource(10_000);   // not aligned to 0 on purpose

    private RateLimiter create(Algorithm algorithm, int limit, long windowMillis) {
        return algorithm.create(new RateLimitConfig(limit, Duration.ofMillis(windowMillis)), clock);
    }

    // ------------------------------------------------------------------ properties shared by all five

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void allowsUpToTheLimitThenDenies(Algorithm algorithm) {
        RateLimiter limiter = create(algorithm, 3, 1000);

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("u").allowed(), algorithm + " request " + (i + 1));
        }
        Decision denied = limiter.tryAcquire("u");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMillis() > 0);
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void keysAreIndependent(Algorithm algorithm) {
        RateLimiter limiter = create(algorithm, 2, 1000);
        limiter.tryAcquire("alice");
        limiter.tryAcquire("alice");

        assertFalse(limiter.tryAcquire("alice").allowed());
        assertTrue(limiter.tryAcquire("bob").allowed());
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void recoversAfterAFullWindow(Algorithm algorithm) {
        RateLimiter limiter = create(algorithm, 3, 1000);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("u");
        }
        clock.advance(2000);

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("u").allowed(), algorithm + " after recovery, request " + (i + 1));
        }
    }

    /** retryAfter is exact: waiting 1 ms less is still denied, waiting the full time succeeds. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void retryAfterIsExact(Algorithm algorithm) {
        Random random = new Random(algorithm.ordinal());
        for (int trial = 0; trial < 200; trial++) {
            ManualTimeSource t = new ManualTimeSource(random.nextInt(5000));
            RateLimiter limiter = algorithm.create(new RateLimitConfig(1 + random.nextInt(6),
                    Duration.ofMillis(100 + random.nextInt(1900))), t);

            Decision d;
            while ((d = limiter.tryAcquire("u")).allowed()) {
                t.advance(random.nextInt(40));        // hit the limit with slightly spread traffic
            }
            long wait = d.retryAfterMillis();

            if (wait > 1) {
                ManualTimeSource probeClock = t;       // probe on the same limiter: a denial consumes nothing
                probeClock.advance(wait - 1);
                assertFalse(limiter.tryAcquire("u").allowed(), algorithm + " trial " + trial + ": too early");
                probeClock.advance(1);
            } else {
                t.advance(wait);
            }
            assertTrue(limiter.tryAcquire("u").allowed(), algorithm + " trial " + trial + ": retryAfter too long");
        }
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void neverAllowsMoreThanLimitPlusBurstOverLongRuns(Algorithm algorithm) {
        // Long-run guarantee for every algorithm: in T ms, at most limit x (T/window + 2) requests pass.
        RateLimiter limiter = create(algorithm, 10, 1000);
        Random random = new Random(5);
        int allowed = 0;
        long start = clock.nowMillis();
        for (int i = 0; i < 20_000; i++) {
            clock.advance(random.nextInt(3));
            if (limiter.tryAcquire("u").allowed()) {
                allowed++;
            }
        }
        long elapsed = clock.nowMillis() - start;
        long bound = 10 * (elapsed / 1000 + 2);
        assertTrue(allowed <= bound, algorithm + " allowed " + allowed + " > " + bound);
        assertTrue(allowed >= 10 * (elapsed / 1000) * 0.9, algorithm + " too strict: " + allowed);
    }

    // ------------------------------------------------------------------ algorithm-specific behaviour

    @Test
    void tokenBucketAllowsBurstThenSteadyRate() {
        RateLimiter limiter = create(Algorithm.TOKEN_BUCKET, 10, 1000);   // 1 token / 100 ms
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("u").allowed());                // full bucket = burst of 10
        }
        assertFalse(limiter.tryAcquire("u").allowed());

        clock.advance(100);
        assertTrue(limiter.tryAcquire("u").allowed());                    // exactly one token refilled
        assertFalse(limiter.tryAcquire("u").allowed());

        clock.advance(250);
        assertTrue(limiter.tryAcquire("u").allowed());
        assertTrue(limiter.tryAcquire("u").allowed());
        assertFalse(limiter.tryAcquire("u").allowed());                   // 2.5 tokens → 2 requests
    }

    @Test
    void tokenBucketNeverExceedsCapacityAfterLongIdle() {
        RateLimiter limiter = create(Algorithm.TOKEN_BUCKET, 5, 1000);
        limiter.tryAcquire("u");
        clock.advance(3_600_000);                                         // an hour idle

        int burst = 0;
        while (limiter.tryAcquire("u").allowed()) {
            burst++;
        }
        assertEquals(5, burst);
    }

    @Test
    void fixedWindowHasTheBoundaryBurst() {
        RateLimiter limiter = create(Algorithm.FIXED_WINDOW, 5, 1000);
        clock.set(10_950);                                                // 50 ms before a boundary
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("u").allowed()) {
                allowed++;
            }
        }
        clock.set(11_000);                                                // new window starts
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("u").allowed()) {
                allowed++;
            }
        }
        assertEquals(10, allowed, "2x the limit within 50 ms: the known weakness");
    }

    @Test
    void slidingWindowLogIsExactForEveryWindowPosition() {
        RateLimiter limiter = create(Algorithm.SLIDING_WINDOW_LOG, 7, 1000);
        Random random = new Random(11);
        List<Long> allowedAt = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            clock.advance(random.nextInt(60));
            if (limiter.tryAcquire("u").allowed()) {
                allowedAt.add(clock.nowMillis());
            }
        }
        // Brute force: for every allowed request, count allowed requests in the 1000 ms ending there.
        for (int i = 0; i < allowedAt.size(); i++) {
            int inWindow = 0;
            for (int j = i; j >= 0 && allowedAt.get(j) > allowedAt.get(i) - 1000; j--) {
                inWindow++;
            }
            assertTrue(inWindow <= 7, "window ending at " + allowedAt.get(i) + " holds " + inWindow);
        }
    }

    @Test
    void slidingWindowCounterSmoothsTheBoundary() {
        RateLimiter limiter = create(Algorithm.SLIDING_WINDOW_COUNTER, 10, 1000);
        clock.set(10_900);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("u").allowed());                // fills the window at its end
        }
        clock.set(11_000);                                                // new window, previous weight 100%
        assertFalse(limiter.tryAcquire("u").allowed());
        clock.set(11_500);                                                // previous weight 50% → estimate 5
        int allowed = 0;
        while (limiter.tryAcquire("u").allowed()) {
            allowed++;
        }
        assertEquals(5, allowed);
    }

    @Test
    void leakyBucketShapesTrafficIntoEvenSpacing() {
        RateLimiter limiter = create(Algorithm.LEAKY_BUCKET, 4, 1000);    // one every 250 ms, queue of 4

        assertEquals(0, limiter.tryAcquire("u").delayMillis());
        assertEquals(250, limiter.tryAcquire("u").delayMillis());
        assertEquals(500, limiter.tryAcquire("u").delayMillis());
        assertEquals(750, limiter.tryAcquire("u").delayMillis());
        Decision full = limiter.tryAcquire("u");
        assertFalse(full.allowed());
        assertEquals(250, full.retryAfterMillis());

        clock.advance(250);                                               // one request leaked out
        Decision next = limiter.tryAcquire("u");
        assertTrue(next.allowed());
        assertEquals(750, next.delayMillis());
    }

    // ------------------------------------------------------------------ composite, service, headers

    @Test
    void compositeRequiresEveryRule() {
        RateLimiter perSecond = create(Algorithm.TOKEN_BUCKET, 3, 1000);
        RateLimiter perMinute = create(Algorithm.SLIDING_WINDOW_LOG, 5, 60_000);
        RateLimiter both = CompositeRateLimiter.of(perSecond, perMinute);

        assertEquals(3, both.limit());
        for (int i = 0; i < 3; i++) {
            assertTrue(both.tryAcquire("u").allowed());
        }
        assertFalse(both.tryAcquire("u").allowed());                      // per-second rule
        clock.advance(1000);
        assertTrue(both.tryAcquire("u").allowed());
        assertTrue(both.tryAcquire("u").allowed());
        Decision quota = both.tryAcquire("u");                            // per-minute quota (5) used up
        assertFalse(quota.allowed());
        assertTrue(quota.retryAfterMillis() > 50_000);
    }

    @Test
    void serviceAppliesPerEndpointRulesPerClient() {
        RateLimiterService service = new RateLimiterService(Algorithm.TOKEN_BUCKET, RateLimitConfig.perSecond(100), clock);
        service.setRule("/login", Algorithm.SLIDING_WINDOW_LOG, RateLimitConfig.perMinute(3));

        for (int i = 0; i < 3; i++) {
            assertTrue(service.check("alice", "/login").allowed());
        }
        assertFalse(service.check("alice", "/login").allowed());          // strict login rule
        assertTrue(service.check("bob", "/login").allowed());             // other client unaffected
        assertTrue(service.check("alice", "/search").allowed());          // other endpoint, default rule
    }

    @Test
    void httpHeaders() {
        RateLimiterService service = new RateLimiterService(Algorithm.FIXED_WINDOW, RateLimitConfig.perSecond(2), clock);
        clock.set(20_000);
        Decision first = service.check("c", "/x");
        service.check("c", "/x");
        Decision denied = service.check("c", "/x");
        clock.advance(1);

        assertEquals(Map.of("X-RateLimit-Limit", "2", "X-RateLimit-Remaining", "1"), service.headers("/x", first));
        assertEquals("1", service.headers("/x", denied).get("Retry-After"));        // 1000 ms → 1 s
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> clock.advance(-1));
        assertThrows(NullPointerException.class, () -> create(Algorithm.TOKEN_BUCKET, 1, 1000).tryAcquire(null));
    }

    // ------------------------------------------------------------------ concurrency

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void concurrentCallersNeverExceedTheLimit(Algorithm algorithm) throws Exception {
        RateLimiter limiter = create(algorithm, 1000, 60_000);            // clock frozen: exactly 1000 may pass
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++) {
                    if (limiter.tryAcquire("shared").allowed()) {
                        allowed.incrementAndGet();
                    }
                    limiter.tryAcquire("other-" + (i % 50));             // plenty of distinct keys too
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1000, allowed.get(), algorithm + ": lost updates would allow more than 1000");
        assertEquals(51, ((KeyedRateLimiter<?>) limiter).trackedKeys());
    }
}
