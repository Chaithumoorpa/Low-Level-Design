package com.lld.ds.ratelimiter.algorithm;

import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.TimeSource;

/**
 * Leaky bucket as a <b>queue</b>: requests drip out at a constant rate of one every
 * {@code window / limit} ms, and the bucket (queue) holds at most {@code limit} waiting requests.
 *
 * <p>Instead of a real queue, each accepted request is given a virtual departure time
 * {@code max(now, lastDeparture + interval)}; the queue is full when that departure would be more than
 * {@code (limit - 1) x interval} in the future. This is the classic "virtual scheduling" formulation
 * (the idea behind GCRA). O(1) memory per key.
 *
 * <p>Unlike the other algorithms, an allowed request may come with a {@code delayMillis}: the caller
 * should wait that long. The output is perfectly smooth (traffic shaping), which suits calls to a
 * downstream service with a hard rate.
 */
public class LeakyBucketRateLimiter extends KeyedRateLimiter<LeakyBucketRateLimiter.Queue> {

    static final class Queue {
        double lastDeparture;                            // fractional ms: no rounding drift

        Queue(double lastDeparture) {
            this.lastDeparture = lastDeparture;
        }
    }

    private final double intervalMillis;
    private final double maxBacklogMillis;

    public LeakyBucketRateLimiter(RateLimitConfig config, TimeSource time) {
        super(config, time);
        this.intervalMillis = (double) config.windowMillis() / config.limit();
        this.maxBacklogMillis = (config.limit() - 1) * intervalMillis;
    }

    @Override
    protected Queue newState(long now) {
        return new Queue(Double.NEGATIVE_INFINITY);           // empty queue
    }

    @Override
    protected Decision decide(Queue q, long now) {
        double departure = Math.max(now, q.lastDeparture + intervalMillis);
        double backlog = departure - now;                     // how long this request would wait
        if (backlog > maxBacklogMillis + 1e-9) {
            long retry = (long) Math.ceil(backlog - maxBacklogMillis - 1e-9);               // same tolerance as the check
            return Decision.deny(retry);
        }
        q.lastDeparture = departure;
        long queuedAhead = (long) Math.floor(backlog / intervalMillis + 1e-9);
        long remaining = config.limit() - 1 - queuedAhead;
        return Decision.allowAfter(remaining, (long) Math.ceil(backlog));
    }
}
