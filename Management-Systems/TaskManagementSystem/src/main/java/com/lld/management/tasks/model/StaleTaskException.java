package com.lld.management.tasks.model;

/** Optimistic locking: the task changed since the caller read it. Reload and try again. */
public class StaleTaskException extends TaskException {

    public StaleTaskException(String taskId, long expected, long actual) {
        super("Task " + taskId + " was changed by someone else (you had version " + expected
                + ", it is now " + actual + ")");
    }
}
