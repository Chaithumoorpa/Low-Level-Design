package com.lld.management.restaurant.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that moves only when told to, so the demo and tests can run a whole evening instantly. */
public final class ManualClock extends Clock {

    private Instant now;

    public ManualClock(Instant start) {
        this.now = start;
    }

    public synchronized void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
