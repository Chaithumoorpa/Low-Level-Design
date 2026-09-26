package com.lld.management.tasks.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * The workflow as a transition table. Each status lists where it may go next, so an illegal move
 * (TODO straight to DONE, CANCELLED straight to IN_PROGRESS) is rejected in one place.
 *
 * <pre>
 * TODO -> IN_PROGRESS -> IN_REVIEW -> DONE
 *            back to TODO / back to IN_PROGRESS (changes requested)
 * DONE -> TODO (reopen)      any open status -> CANCELLED -> TODO (restore)
 * </pre>
 */
public enum TaskStatus {
    TODO, IN_PROGRESS, IN_REVIEW, DONE, CANCELLED;

    public Set<TaskStatus> next() {
        return switch (this) {
            case TODO -> EnumSet.of(IN_PROGRESS, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(TODO, IN_REVIEW, CANCELLED);
            case IN_REVIEW -> EnumSet.of(IN_PROGRESS, DONE, CANCELLED);
            case DONE, CANCELLED -> EnumSet.of(TODO);
        };
    }

    public boolean canMoveTo(TaskStatus target) {
        return next().contains(target);
    }

    /** Finished one way or another: no reminders, does not block a parent. */
    public boolean isClosed() {
        return this == DONE || this == CANCELLED;
    }
}
