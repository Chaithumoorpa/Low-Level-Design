package com.lld.ds.ratelimiter.algorithm;

import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.RateLimiter;
import com.lld.ds.ratelimiter.core.TimeSource;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Template Method: everything the five algorithms share.
 *
 * <ul>
 *   <li>one state object per key, created lazily in a {@link ConcurrentHashMap}</li>
 *   <li>per-key locking: requests for different keys never block each other, while requests for the
 *       same key are serialised so the read-modify-write of the counters is atomic</li>
 *   <li>time comes from an injected {@link TimeSource}</li>
 * </ul>
 * Subclasses supply only {@link #newState} and {@link #decide}.
 */
public abstract class KeyedRateLimiter<S> implements RateLimiter {

    protected final RateLimitConfig config;
    private final TimeSource time;
    private final ConcurrentMap<String, S> states = new ConcurrentHashMap<>();

    protected KeyedRateLimiter(RateLimitConfig config, TimeSource time) {
        this.config = Objects.requireNonNull(config);
        this.time = Objects.requireNonNull(time);
    }

    @Override
    public final Decision tryAcquire(String key) {
        Objects.requireNonNull(key, "key");
        long now = time.nowMillis();
        S state = states.computeIfAbsent(key, k -> newState(now));
        synchronized (state) {
            return decide(state, now);
        }
    }

    @Override
    public int limit() {
        return config.limit();
    }

    /** Number of keys currently tracked (memory grows with distinct clients; see README). */
    public int trackedKeys() {
        return states.size();
    }

    /** Fresh state for a key seen for the first time. */
    protected abstract S newState(long now);

    /** Algorithm-specific rule. Called while holding the key's lock. */
    protected abstract Decision decide(S state, long now);
}
