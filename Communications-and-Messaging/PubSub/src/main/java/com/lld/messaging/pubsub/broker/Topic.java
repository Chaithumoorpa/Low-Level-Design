package com.lld.messaging.pubsub.broker;

import java.util.ArrayList;
import java.util.List;

/** A named stream split into partitions, each an independent ordered log. */
final class Topic {

    private final String name;
    private final List<PartitionLog> partitions = new ArrayList<>();
    private final RetentionPolicy retention;
    private final Partitioner partitioner;

    Topic(String name, int partitionCount, RetentionPolicy retention, Partitioner partitioner) {
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("A topic needs at least one partition");
        }
        this.name = name;
        this.retention = retention;
        this.partitioner = partitioner;
        for (int p = 0; p < partitionCount; p++) {
            partitions.add(new PartitionLog(name, p));
        }
    }

    String name() {
        return name;
    }

    int partitionCount() {
        return partitions.size();
    }

    PartitionLog partition(int p) {
        return partitions.get(p);
    }

    List<PartitionLog> partitions() {
        return partitions;
    }

    RetentionPolicy retention() {
        return retention;
    }

    Partitioner partitioner() {
        return partitioner;
    }
}
