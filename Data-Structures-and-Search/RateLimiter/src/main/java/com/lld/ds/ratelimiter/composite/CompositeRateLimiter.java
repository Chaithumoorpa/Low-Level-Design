package com.lld.ds.ratelimiter.composite;

import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.RateLimiter;

import java.util.List;

/**
 * Composite pattern: several limits that must ALL pass, e.g. "10 per second" (burst protection)
 * AND "1000 per hour" (quota), treated as one {@link RateLimiter}.
 *
 * <p>Rules are checked in order and the first denial stops the chain. Rules checked before the
 * denying one have already consumed a permit: the attempt counts against them. That is the
 * common, conservative choice (clients that keep retrying are slowed further). An exact "all or
 * nothing" version would need a peek/reserve/commit API on every algorithm (see README).
 */
public class CompositeRateLimiter implements RateLimiter {

    private final List<RateLimiter> rules;

    public CompositeRateLimiter(List<RateLimiter> rules) {
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule is required");
        }
        this.rules = List.copyOf(rules);
    }

    public static CompositeRateLimiter of(RateLimiter... rules) {
        return new CompositeRateLimiter(List.of(rules));
    }

    @Override
    public Decision tryAcquire(String key) {
        long remaining = Long.MAX_VALUE;
        long delay = 0;
        for (RateLimiter rule : rules) {
            Decision d = rule.tryAcquire(key);
            if (!d.allowed()) {
                return d;
            }
            remaining = Math.min(remaining, d.remaining());     // the tightest rule decides
            delay = Math.max(delay, d.delayMillis());
        }
        return delay > 0 ? Decision.allowAfter(remaining, delay) : Decision.allow(remaining);
    }

    /** The tightest limit, used for response headers. */
    @Override
    public int limit() {
        return rules.stream().mapToInt(RateLimiter::limit).min().orElseThrow();
    }
}
