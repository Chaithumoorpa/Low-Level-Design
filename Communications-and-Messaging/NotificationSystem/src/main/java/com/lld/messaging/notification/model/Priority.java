package com.lld.messaging.notification.model;

/** Declared lowest first: {@code compareTo} orders by urgency. HIGH ignores quiet hours. */
public enum Priority {
    LOW, NORMAL, HIGH
}
