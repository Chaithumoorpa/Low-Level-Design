package com.lld.ds.ratelimiter.core;

import java.time.Duration;

/**
 * "At most {@code limit} requests per {@code window}", e.g. 100 per minute.
 * Every algorithm interprets it in its own way (see README), but the long-run rate is always
 * {@code limit / window}.
 */
public record RateLimitConfig(int limit, Duration window) {

    public RateLimitConfig {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        if (window == null || window.toMillis() < 1) {
            throw new IllegalArgumentException("window must be at least 1 ms");
        }
    }

    public static RateLimitConfig perSecond(int limit) {
        return new RateLimitConfig(limit, Duration.ofSeconds(1));
    }

    public static RateLimitConfig perMinute(int limit) {
        return new RateLimitConfig(limit, Duration.ofMinutes(1));
    }

    public long windowMillis() {
        return window.toMillis();
    }

    @Override
    public String toString() {
        return limit + " per " + window.toMillis() + " ms";
    }
}
