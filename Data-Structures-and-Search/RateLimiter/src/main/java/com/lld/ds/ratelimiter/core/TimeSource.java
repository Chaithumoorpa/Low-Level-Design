package com.lld.ds.ratelimiter.core;

/**
 * Where "now" comes from. Injected everywhere so tests can move time by hand instead of sleeping,
 * which makes every timing test exact and instant.
 */
@FunctionalInterface
public interface TimeSource {

    long nowMillis();

    /** Monotonic wall time for production use. */
    static TimeSource system() {
        long originNanos = System.nanoTime();
        long originMillis = System.currentTimeMillis();
        return () -> originMillis + (System.nanoTime() - originNanos) / 1_000_000;
    }
}
