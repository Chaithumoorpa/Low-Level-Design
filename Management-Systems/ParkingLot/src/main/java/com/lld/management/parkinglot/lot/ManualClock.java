package com.lld.management.parkinglot.lot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that moves only when told to: lets the demo and tests simulate hours in milliseconds. */
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
