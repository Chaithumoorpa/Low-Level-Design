package com.lld.messaging.pubsub.model;

/** Address of one partition: the unit of ordering, of parallelism and of assignment. */
public record TopicPartition(String topic, int partition) implements Comparable<TopicPartition> {

    @Override
    public int compareTo(TopicPartition o) {
        int c = topic.compareTo(o.topic);
        return c != 0 ? c : Integer.compare(partition, o.partition);
    }

    @Override
    public String toString() {
        return topic + "-" + partition;
    }
}
