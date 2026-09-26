package com.lld.messaging.pubsub.broker;

import com.lld.messaging.pubsub.consumer.AssignmentStrategy;
import com.lld.messaging.pubsub.consumer.ConsumerGroup;
import com.lld.messaging.pubsub.model.Message;
import com.lld.messaging.pubsub.model.PubSubException;
import com.lld.messaging.pubsub.model.TopicPartition;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds topics and consumer groups. Publishers append to partition logs; subscribers read the logs by
 * offset. The broker never tracks "who read what" per message: each consumer group keeps one committed
 * offset per partition, which is what makes fan-out to many groups cheap.
 *
 * <p>Thread safety: topics are created once and looked up in a concurrent map; each partition log
 * synchronises its own appends, so publishers to different partitions never block each other.
 */
public final class Broker {

    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private final Map<String, ConsumerGroup> groups = new ConcurrentHashMap<>();
    private final Clock clock;

    public Broker(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ topics

    public void createTopic(String name, int partitions, RetentionPolicy retention) {
        createTopic(name, partitions, retention, Partitioner.keyHashOrRoundRobin());
    }

    public void createTopic(String name, int partitions, RetentionPolicy retention, Partitioner partitioner) {
        if (topics.putIfAbsent(name, new Topic(name, partitions, retention, partitioner)) != null) {
            throw new PubSubException("Topic " + name + " already exists");
        }
    }

    public boolean hasTopic(String name) {
        return topics.containsKey(name);
    }

    public int partitionCount(String topic) {
        return topic(topic).partitionCount();
    }

    public List<TopicPartition> partitions(String topic) {
        List<TopicPartition> out = new ArrayList<>();
        for (int p = 0; p < topic(topic).partitionCount(); p++) {
            out.add(new TopicPartition(topic, p));
        }
        return out;
    }

    // ------------------------------------------------------------------ publish

    public Message publish(String topic, String key, String value) {
        return publish(topic, key, value, Map.of());
    }

    /** Appends to the partition chosen by the topic's partitioner. Returns the stored message (with its offset). */
    public Message publish(String topic, String key, String value, Map<String, String> headers) {
        Topic t = topic(topic);
        int p = t.partitioner().partition(key, t.partitionCount());
        return t.partition(p).append(key, value, headers, clock.instant());
    }

    // ------------------------------------------------------------------ read

    public List<Message> fetch(TopicPartition tp, long fromOffset, int max) {
        return log(tp).read(fromOffset, max);
    }

    public long startOffset(TopicPartition tp) {
        return log(tp).startOffset();
    }

    public long endOffset(TopicPartition tp) {
        return log(tp).endOffset();
    }

    /** Drops messages beyond each topic's retention. Run periodically. @return messages dropped */
    public int enforceRetention() {
        int dropped = 0;
        for (Topic t : topics.values()) {
            for (PartitionLog log : t.partitions()) {
                dropped += log.applyRetention(t.retention(), clock.instant());
            }
        }
        return dropped;
    }

    // ------------------------------------------------------------------ groups

    /** Gets or creates a consumer group. A group id belongs to one topic. */
    public ConsumerGroup group(String groupId, String topic, AssignmentStrategy strategy, ConsumerGroup.OffsetReset reset) {
        topic(topic);
        ConsumerGroup g = groups.computeIfAbsent(groupId, id -> new ConsumerGroup(this, id, topic, strategy, reset));
        if (!g.topic().equals(topic)) {
            throw new PubSubException("Group " + groupId + " already consumes " + g.topic());
        }
        return g;
    }

    public ConsumerGroup group(String groupId, String topic) {
        return group(groupId, topic, AssignmentStrategy.range(), ConsumerGroup.OffsetReset.EARLIEST);
    }

    private Topic topic(String name) {
        Topic t = topics.get(name);
        if (t == null) {
            throw new PubSubException("No topic " + name);
        }
        return t;
    }

    private PartitionLog log(TopicPartition tp) {
        Topic t = topic(tp.topic());
        if (tp.partition() < 0 || tp.partition() >= t.partitionCount()) {
            throw new PubSubException("No partition " + tp);
        }
        return t.partition(tp.partition());
    }
}
