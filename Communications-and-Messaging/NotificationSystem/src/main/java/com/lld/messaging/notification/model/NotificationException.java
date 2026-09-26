package com.lld.messaging.notification.model;

/** A request that can't be accepted at all: unknown user or template, missing variable... */
public class NotificationException extends RuntimeException {

    public NotificationException(String message) {
        super(message);
    }
}
