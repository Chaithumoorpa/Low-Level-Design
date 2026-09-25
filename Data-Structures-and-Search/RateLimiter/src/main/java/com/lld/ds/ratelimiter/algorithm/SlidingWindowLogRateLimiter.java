package com.lld.ds.ratelimiter.algorithm;

import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.TimeSource;

import java.util.ArrayDeque;

/**
 * Sliding window log: remember the timestamp of every allowed request in the last {@code window};
 * allow a new one only if fewer than {@code limit} are remembered.
 *
 * <p><b>Exact</b>: no window of length {@code window}, placed anywhere in time, ever contains more
 * than {@code limit} allowed requests. The cost is memory, up to {@code limit} timestamps per key.
 */
public class SlidingWindowLogRateLimiter extends KeyedRateLimiter<ArrayDeque<Long>> {

    public SlidingWindowLogRateLimiter(RateLimitConfig config, TimeSource time) {
        super(config, time);
    }

    @Override
    protected ArrayDeque<Long> newState(long now) {
        return new ArrayDeque<>();
    }

    @Override
    protected Decision decide(ArrayDeque<Long> log, long now) {
        long windowStart = now - config.windowMillis();
        while (!log.isEmpty() && log.peekFirst() <= windowStart) {
            log.pollFirst();                             // forget requests that slid out of the window
        }
        if (log.size() < config.limit()) {
            log.addLast(now);
            return Decision.allow(config.limit() - log.size());
        }
        return Decision.deny(log.peekFirst() + config.windowMillis() - now);
    }
}
