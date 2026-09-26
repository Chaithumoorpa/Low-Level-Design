package com.lld.messaging.pubsub.broker;

import java.time.Duration;

/**
 * How long messages are kept, whether or not anyone has read them. A pub-sub log is not a queue:
 * consuming does not delete; retention does.
 */
public record RetentionPolicy(int maxMessagesPerPartition, Duration maxAge) {

    public RetentionPolicy {
        if (maxMessagesPerPartition <= 0) {
            throw new IllegalArgumentException("Keep at least one message");
        }
    }

    public static RetentionPolicy keepForever() {
        return new RetentionPolicy(Integer.MAX_VALUE, Duration.ofDays(365_000));
    }
}
