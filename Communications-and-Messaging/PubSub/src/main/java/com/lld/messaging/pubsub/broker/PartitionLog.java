package com.lld.messaging.pubsub.broker;

import com.lld.messaging.pubsub.model.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Append-only log of one partition. Offsets start at 0 and grow by one per message. Retention removes
 * messages from the <em>head</em> only, so {@code startOffset} moves forward but offsets are never reused.
 */
final class PartitionLog {

    private final String topic;
    private final int partition;
    private final List<Message> messages = new ArrayList<>();
    private long startOffset;

    PartitionLog(String topic, int partition) {
        this.topic = topic;
        this.partition = partition;
    }

    synchronized Message append(String key, String value, Map<String, String> headers, Instant at) {
        Message m = new Message(topic, partition, endOffset(), key, value, headers, at);
        messages.add(m);
        return m;
    }

    /** Up to {@code max} messages starting at {@code from} (clamped to the retained range). */
    synchronized List<Message> read(long from, int max) {
        long first = Math.max(from, startOffset);
        int index = (int) (first - startOffset);
        if (index >= messages.size()) {
            return List.of();
        }
        return List.copyOf(messages.subList(index, Math.min(messages.size(), index + max)));
    }

    /** Oldest offset still stored. */
    synchronized long startOffset() {
        return startOffset;
    }

    /** Offset the next message will get (= one past the last). */
    synchronized long endOffset() {
        return startOffset + messages.size();
    }

    /** @return how many messages were dropped */
    synchronized int applyRetention(RetentionPolicy policy, Instant now) {
        int drop = 0;
        while (drop < messages.size()
                && (messages.size() - drop > policy.maxMessagesPerPartition()
                || messages.get(drop).timestamp().isBefore(now.minus(policy.maxAge())))) {
            drop++;
        }
        if (drop > 0) {
            messages.subList(0, drop).clear();
            startOffset += drop;
        }
        return drop;
    }
}
