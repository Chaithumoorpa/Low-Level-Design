package com.lld.messaging.pubsub.model;

/** Unknown topic, bad configuration, or a commit that is no longer allowed. */
public class PubSubException extends RuntimeException {

    public PubSubException(String message) {
        super(message);
    }
}
