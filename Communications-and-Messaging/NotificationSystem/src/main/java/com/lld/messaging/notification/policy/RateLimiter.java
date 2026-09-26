package com.lld.messaging.notification.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sliding-window limit per key (here: user + channel), e.g. "at most 3 SMS per user per hour".
 * Protects users from spam and the company from SMS bills. Only successful sends are counted.
 * Not thread-safe; the notification service calls it under its lock.
 */
public final class RateLimiter {

    private final int limit;
    private final Duration window;
    private final Map<String, Deque<Instant>> sent = new HashMap<>();

    public RateLimiter(int limit, Duration window) {
        this.limit = limit;
        this.window = window;
    }

    /** @return {@code now} if a send is allowed, otherwise the earliest moment it will be */
    public Instant allowedAt(String key, Instant now) {
        Deque<Instant> times = sent.getOrDefault(key, new ArrayDeque<>());
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(window))) {
            times.pollFirst();
        }
        if (times.size() < limit) {
            return now;
        }
        return times.peekFirst().plus(window);
    }

    public void record(String key, Instant at) {
        sent.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(at);
    }
}
