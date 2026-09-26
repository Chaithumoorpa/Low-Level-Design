package com.lld.management.tasks.model;

/** A request that breaks a business rule (bad transition, no permission, unknown task...). */
public class TaskException extends RuntimeException {

    public TaskException(String message) {
        super(message);
    }
}
