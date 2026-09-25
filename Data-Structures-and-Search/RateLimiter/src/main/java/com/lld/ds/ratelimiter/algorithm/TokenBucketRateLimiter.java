package com.lld.ds.ratelimiter.algorithm;

import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.TimeSource;

/**
 * Token bucket: a bucket holds up to {@code limit} tokens and refills continuously at
 * {@code limit / window}. Each request takes one token.
 *
 * <p>Allows bursts up to the bucket size, then a steady rate. O(1) memory per key.
 * Refilling is computed lazily from the elapsed time, so no background timer is needed.
 */
public class TokenBucketRateLimiter extends KeyedRateLimiter<TokenBucketRateLimiter.Bucket> {

    static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(double tokens, long lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }

    private static final double EPSILON = 1e-9;

    private final double capacity;
    private final double tokensPerMilli;

    public TokenBucketRateLimiter(RateLimitConfig config, TimeSource time) {
        super(config, time);
        this.capacity = config.limit();
        this.tokensPerMilli = (double) config.limit() / config.windowMillis();
    }

    @Override
    protected Bucket newState(long now) {
        return new Bucket(capacity, now);                 // start full: a new client may burst
    }

    @Override
    protected Decision decide(Bucket b, long now) {
        long elapsed = Math.max(0, now - b.lastRefill);
        b.tokens = Math.min(capacity, b.tokens + elapsed * tokensPerMilli);
        b.lastRefill = now;

        if (b.tokens >= 1 - EPSILON) {                     // tolerate floating-point error
            b.tokens = Math.max(0, b.tokens - 1);
            return Decision.allow((long) Math.floor(b.tokens));
        }
        long waitMillis = (long) Math.ceil((1 - EPSILON - b.tokens) / tokensPerMilli);   // same tolerance as above
        return Decision.deny(waitMillis);
    }
}
