package com.lld.messaging.pubsub.consumer;

import com.lld.messaging.pubsub.broker.Broker;
import com.lld.messaging.pubsub.broker.RetentionPolicy;
import com.lld.messaging.pubsub.model.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Push-style subscription on top of the pull consumer: calls a handler for each message, in partition
 * order. A handler that keeps failing (a "poison" message) doesn't block the partition forever: after
 * {@code maxAttempts} the message is copied to a <b>dead-letter topic</b> with the error, committed, and
 * processing moves on. A filter lets a subscriber ignore messages it doesn't care about.
 */
public final class PushSubscription {

    @FunctionalInterface
    public interface Handler {
        void handle(Message message) throws Exception;
    }

    private final Broker broker;
    private final Consumer consumer;
    private final Handler handler;
    private final Predicate<Message> filter;
    private final int maxAttempts;
    private final String deadLetterTopic;
    private int delivered;
    private int filtered;
    private int deadLettered;

    public PushSubscription(Broker broker, Consumer consumer, Handler handler, Predicate<Message> filter,
                            int maxAttempts, String deadLetterTopic) {
        this.broker = broker;
        this.consumer = consumer;
        this.handler = Objects.requireNonNull(handler);
        this.filter = filter == null ? m -> true : filter;
        this.maxAttempts = maxAttempts;
        this.deadLetterTopic = deadLetterTopic;
        synchronized (PushSubscription.class) {
            if (!broker.hasTopic(deadLetterTopic)) {
                broker.createTopic(deadLetterTopic, 1, RetentionPolicy.keepForever());
            }
        }
    }

    /** Polls once and handles the batch. @return messages taken from the topic */
    public int pump(int maxMessages) {
        List<Message> batch = consumer.poll(maxMessages);
        for (Message m : batch) {
            if (!filter.test(m)) {
                filtered++;
            } else {
                Exception last = null;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    try {
                        handler.handle(m);
                        last = null;
                        break;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (last == null) {
                    delivered++;
                } else {
                    Map<String, String> headers = new HashMap<>(m.headers());
                    headers.put("dlq.source", m.topicPartition() + "@" + m.offset());
                    headers.put("dlq.error", String.valueOf(last.getMessage()));
                    headers.put("dlq.attempts", String.valueOf(maxAttempts));
                    broker.publish(deadLetterTopic, m.key(), m.value(), headers);
                    deadLettered++;
                }
            }
            consumer.commit(m);                           // progress is saved message by message
        }
        return batch.size();
    }

    /** Pumps until the subscription is caught up. */
    public void drain() {
        while (pump(100) > 0) {
            // keep going
        }
    }

    public int delivered() {
        return delivered;
    }

    public int filtered() {
        return filtered;
    }

    public int deadLettered() {
        return deadLettered;
    }
}
