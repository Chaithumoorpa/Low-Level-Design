package com.lld.messaging.notification.policy;

import java.time.Duration;

/**
 * Exponential backoff: wait {@code base}, then {@code base*2}, {@code base*4}... capped at {@code max},
 * for at most {@code maxAttempts} tries. Backing off gives a struggling provider room to recover
 * instead of hammering it. (Production systems add random jitter so retries don't arrive in waves.)
 */
public record RetryPolicy(int maxAttempts, Duration base, Duration max) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("At least one attempt");
        }
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(4, Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    /** Delay after the given (1-based) failed attempt. */
    public Duration delayAfter(int attempt) {
        long factor = 1L << Math.min(attempt - 1, 30);
        Duration d = base.multipliedBy(factor);
        return d.compareTo(max) > 0 ? max : d;
    }

    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }
}
