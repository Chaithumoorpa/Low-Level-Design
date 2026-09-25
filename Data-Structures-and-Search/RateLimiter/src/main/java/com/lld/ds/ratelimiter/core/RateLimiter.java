package com.lld.ds.ratelimiter.core;

/**
 * Decides whether a request identified by {@code key} (user id, API key, IP, "user:endpoint", ...)
 * may proceed. Every key has its own independent budget. Implementations must be thread-safe.
 */
public interface RateLimiter {

    /** Consumes one permit if available. */
    Decision tryAcquire(String key);

    /** Configured limit, for response headers. */
    int limit();
}
