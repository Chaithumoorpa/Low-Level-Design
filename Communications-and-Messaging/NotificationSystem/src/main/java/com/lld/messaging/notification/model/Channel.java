package com.lld.messaging.notification.model;

/** Ways to reach a user. Each needs a different piece of contact data (IN_APP needs none). */
public enum Channel {
    EMAIL, SMS, PUSH, IN_APP;

    /** Does this contact have what the channel needs (address, number, device token)? */
    public boolean reachable(Contact contact) {
        return switch (this) {
            case EMAIL -> contact.email() != null;
            case SMS -> contact.phone() != null;
            case PUSH -> contact.pushToken() != null;
            case IN_APP -> true;
        };
    }
}
