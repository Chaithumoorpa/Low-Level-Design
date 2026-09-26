package com.lld.messaging.notification.channel;

/**
 * A provider error. <b>Temporary</b> (timeout, 503, throttled): worth retrying later.
 * <b>Permanent</b> (invalid number, unregistered device, hard bounce): retrying only wastes money.
 */
public class SendFailure extends Exception {

    private final boolean permanent;

    public SendFailure(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public static SendFailure temporary(String message) {
        return new SendFailure(message, false);
    }

    public static SendFailure permanent(String message) {
        return new SendFailure(message, true);
    }

    public boolean permanent() {
        return permanent;
    }
}
