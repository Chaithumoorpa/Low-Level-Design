package com.lld.messaging.pubsub.broker;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Chooses a partition for a message. Same key → same partition → messages for one key (one order,
 * one user) stay in order. Keyless messages are spread round-robin for load balance.
 */
public interface Partitioner {

    int partition(String key, int partitions);

    static Partitioner keyHashOrRoundRobin() {
        AtomicLong counter = new AtomicLong();
        return (key, partitions) -> key == null
                ? (int) Math.floorMod(counter.getAndIncrement(), (long) partitions)
                : Math.floorMod(key.hashCode(), partitions);
    }
}
