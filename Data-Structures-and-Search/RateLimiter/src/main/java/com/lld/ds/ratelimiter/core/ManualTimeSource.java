package com.lld.ds.ratelimiter.core;

import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when told to. Used by tests and the demo's simulated timeline. */
public class ManualTimeSource implements TimeSource {

    private final AtomicLong now;

    public ManualTimeSource() {
        this(0);
    }

    public ManualTimeSource(long startMillis) {
        this.now = new AtomicLong(startMillis);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void advance(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("Time cannot go backwards");
        }
        now.addAndGet(millis);
    }

    public void set(long millis) {
        if (millis < now.get()) {
            throw new IllegalArgumentException("Time cannot go backwards");
        }
        now.set(millis);
    }
}
