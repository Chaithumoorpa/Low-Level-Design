package com.lld.ds.ratelimiter.algorithm;

import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.TimeSource;

/**
 * Fixed window counter: time is cut into windows aligned to multiples of {@code window}
 * (e.g. 12:00:00-12:00:59), each with a counter that resets at the boundary.
 *
 * <p>Simplest and cheapest (one counter per key), but it has the famous <b>boundary burst</b>:
 * {@code limit} requests at the very end of one window plus {@code limit} at the start of the next
 * gives 2x the limit within a single window length.
 */
public class FixedWindowRateLimiter extends KeyedRateLimiter<FixedWindowRateLimiter.Window> {

    static final class Window {
        long start;
        int count;
    }

    public FixedWindowRateLimiter(RateLimitConfig config, TimeSource time) {
        super(config, time);
    }

    @Override
    protected Window newState(long now) {
        Window w = new Window();
        w.start = windowStart(now);
        return w;
    }

    @Override
    protected Decision decide(Window w, long now) {
        long start = windowStart(now);
        if (start != w.start) {                      // a new window began: reset
            w.start = start;
            w.count = 0;
        }
        if (w.count < config.limit()) {
            w.count++;
            return Decision.allow(config.limit() - w.count);
        }
        return Decision.deny(w.start + config.windowMillis() - now);
    }

    private long windowStart(long now) {
        return Math.floorDiv(now, config.windowMillis()) * config.windowMillis();
    }
}
