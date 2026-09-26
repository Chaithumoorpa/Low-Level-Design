package com.lld.messaging.pubsub.model;

import java.time.Instant;
import java.util.Map;

/**
 * An immutable record in a partition's log. {@code offset} is its position in that partition and never
 * changes, which is what lets many independent subscribers read the same log at their own pace.
 */
public record Message(String topic, int partition, long offset, String key, String value,
                      Map<String, String> headers, Instant timestamp) {

    public Message {
        headers = Map.copyOf(headers);
    }

    public TopicPartition topicPartition() {
        return new TopicPartition(topic, partition);
    }

    @Override
    public String toString() {
        return topic + "-" + partition + "@" + offset + " " + (key == null ? "" : key + "=") + value;
    }
}
