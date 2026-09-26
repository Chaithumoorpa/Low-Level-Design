package com.lld.management.tasks.model;

import java.time.Instant;

/** One line of a task's audit trail: who changed what, when. Never edited or removed. */
public record Activity(Instant at, User actor, String change) {

    @Override
    public String toString() {
        return at + " " + actor.name() + ": " + change;
    }
}
