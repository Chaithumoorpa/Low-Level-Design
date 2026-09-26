package com.lld.messaging.pubsub.consumer;

import com.lld.messaging.pubsub.model.Message;
import com.lld.messaging.pubsub.model.TopicPartition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One member of a consumer group (typically one thread or one service instance). Pull model:
 * {@link #poll} reads from the assigned partitions; {@link #commit} records progress. Commit
 * <em>after</em> processing: a crash in between means the messages are read again, never lost.
 *
 * <p>Its fields are guarded by the owning group's monitor (the group reassigns them on rebalance).
 */
public final class Consumer {

    private final ConsumerGroup group;
    private final String id;
    int generation;
    List<TopicPartition> assignment = List.of();
    Map<TopicPartition, Long> positions = new LinkedHashMap<>();
    int nextPartition;
    boolean closed;

    Consumer(ConsumerGroup group, String id) {
        this.group = group;
        this.id = id;
    }

    public String id() {
        return id;
    }

    public List<Message> poll(int maxMessages) {
        return group.poll(this, maxMessages);
    }

    /** Commits everything returned by previous polls. */
    public void commit() {
        group.commit(this, group.generationOf(this), group.positionsOf(this));
    }

    /** Commits up to and including {@code message} in its partition. */
    public void commit(Message message) {
        Map<TopicPartition, Long> one = new LinkedHashMap<>();
        one.put(message.topicPartition(), message.offset() + 1);
        group.commit(this, group.generationOf(this), one);
    }

    public List<TopicPartition> assignment() {
        return group.assignmentOf(this);
    }

    /** Leaves the group; its partitions move to the remaining members. */
    public void close() {
        group.leave(this);
    }

    @Override
    public String toString() {
        return id;
    }
}
