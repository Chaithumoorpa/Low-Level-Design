package com.lld.ds.ratelimiter.algorithm;

import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.TimeSource;

/**
 * Sliding window counter: keep only the counts of the current and the previous fixed window, and
 * estimate the last {@code window} ms as
 *
 * <pre>  estimate = previousCount x (share of previous window still inside the sliding window) + currentCount</pre>
 *
 * It assumes requests were spread evenly across the previous window. O(1) memory like the fixed
 * window, and it removes most of the boundary burst. It is an approximation, not exact like the log.
 */
public class SlidingWindowCounterRateLimiter extends KeyedRateLimiter<SlidingWindowCounterRateLimiter.Counters> {

    static final class Counters {
        long currentStart;
        int current;
        int previous;
    }

    public SlidingWindowCounterRateLimiter(RateLimitConfig config, TimeSource time) {
        super(config, time);
    }

    @Override
    protected Counters newState(long now) {
        Counters c = new Counters();
        c.currentStart = windowStart(now);
        return c;
    }

    @Override
    protected Decision decide(Counters c, long now) {
        long window = config.windowMillis();
        long start = windowStart(now);
        if (start != c.currentStart) {
            // Roll forward. If more than one whole window passed, the previous window was empty.
            c.previous = start - c.currentStart == window ? c.current : 0;
            c.current = 0;
            c.currentStart = start;
        }

        long elapsedInCurrent = now - c.currentStart;
        double previousWeight = (double) (window - elapsedInCurrent) / window;
        double estimate = c.previous * previousWeight + c.current;

        if (estimate + 1 <= config.limit() + 1e-9) {        // tolerate floating-point error
            c.current++;
            return Decision.allow((long) Math.floor(config.limit() - (estimate + 1)));
        }
        return Decision.deny(retryAfter(c, elapsedInCurrent, window));
    }

    /**
     * Exact time until one more request fits, assuming no other traffic meanwhile.
     * Phase 1: later in the current window, as the previous window's weight shrinks.
     * Phase 2: in the next window, where today's count becomes the (shrinking) previous count.
     */
    private long retryAfter(Counters c, long elapsedInCurrent, long window) {
        int limit = config.limit();
        long untilNextWindow = window - elapsedInCurrent;
        if (c.previous > 0 && c.current + 1 <= limit) {
            // previous x (window - (elapsed + t)) / window + current + 1 <= limit
            double t = window - elapsedInCurrent - (double) (limit - 1 - c.current) * window / c.previous;
            if (t < untilNextWindow) {
                return Math.max(1, (long) Math.ceil(t - 1e-9));
            }
        }
        // current x (window - u) / window + 1 <= limit, with u = offset inside the next window
        double u = c.current == 0 ? 0 : window - (double) (limit - 1) * window / c.current;
        return Math.max(1, untilNextWindow + Math.max(0, (long) Math.ceil(u - 1e-9)));
    }

    private long windowStart(long now) {
        return Math.floorDiv(now, config.windowMillis()) * config.windowMillis();
    }
}
