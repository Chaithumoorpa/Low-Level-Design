package com.lld.finance.payments.processor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stops sending traffic to a processor that keeps failing, so customers don't wait on timeouts.
 * CLOSED (normal) → after {@code threshold} consecutive failures → OPEN (skip it) → after
 * {@code cooldown} → HALF_OPEN (let one request probe) → success closes it, failure re-opens it.
 */
public final class CircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int threshold;
    private final Duration cooldown;
    private final Clock clock;
    private int consecutiveFailures;
    private Instant openedAt;
    private State state = State.CLOSED;

    public CircuitBreaker(int threshold, Duration cooldown, Clock clock) {
        this.threshold = threshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public synchronized boolean allowRequest() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(cooldown))) {
            state = State.HALF_OPEN;
            return true;                                      // the single probe
        }
        return state == State.CLOSED;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= threshold) {
            state = State.OPEN;
            openedAt = clock.instant();
        }
    }

    public synchronized State state() {
        return state;
    }
}
