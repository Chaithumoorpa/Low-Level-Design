package com.lld.ds.ratelimiter.algorithm;

import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.RateLimiter;
import com.lld.ds.ratelimiter.core.TimeSource;

import java.util.function.BiFunction;

/** Factory: the five classic algorithms, created by name (e.g. from a config file). */
public enum Algorithm {
    TOKEN_BUCKET(TokenBucketRateLimiter::new),
    LEAKY_BUCKET(LeakyBucketRateLimiter::new),
    FIXED_WINDOW(FixedWindowRateLimiter::new),
    SLIDING_WINDOW_LOG(SlidingWindowLogRateLimiter::new),
    SLIDING_WINDOW_COUNTER(SlidingWindowCounterRateLimiter::new);

    private final BiFunction<RateLimitConfig, TimeSource, RateLimiter> constructor;

    Algorithm(BiFunction<RateLimitConfig, TimeSource, RateLimiter> constructor) {
        this.constructor = constructor;
    }

    public RateLimiter create(RateLimitConfig config, TimeSource time) {
        return constructor.apply(config, time);
    }
}
