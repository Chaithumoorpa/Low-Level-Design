package com.lld.messaging.notification.model;

/**
 * Why we are contacting the user. The category drives the rules: marketing can be switched off and
 * dropped when rate limited; security alerts can never be switched off and skip quiet hours.
 */
public enum Category {
    SECURITY, TRANSACTIONAL, MARKETING;

    public Priority defaultPriority() {
        return switch (this) {
            case SECURITY -> Priority.HIGH;
            case TRANSACTIONAL -> Priority.NORMAL;
            case MARKETING -> Priority.LOW;
        };
    }
}
