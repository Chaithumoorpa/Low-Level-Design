package com.lld.messaging.pubsub.consumer;

import com.lld.messaging.pubsub.model.TopicPartition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a topic's partitions among a group's members. Every partition goes to exactly one member
 * (so its order is preserved); members beyond the partition count get nothing and sit idle.
 */
public interface AssignmentStrategy {

    /** @param members sorted member ids; @return member id -> its partitions (every member present) */
    Map<String, List<TopicPartition>> assign(List<TopicPartition> partitions, List<String> members);

    /** Contiguous blocks: 7 partitions, 3 members → [0,1,2] [3,4] [5,6]. */
    static AssignmentStrategy range() {
        return (partitions, members) -> {
            Map<String, List<TopicPartition>> out = empty(members);
            if (members.isEmpty()) {
                return out;
            }
            int per = partitions.size() / members.size();
            int extra = partitions.size() % members.size();
            int next = 0;
            for (int i = 0; i < members.size(); i++) {
                int count = per + (i < extra ? 1 : 0);
                out.get(members.get(i)).addAll(partitions.subList(next, next + count));
                next += count;
            }
            return out;
        };
    }

    /** Dealt like cards: 7 partitions, 3 members → [0,3,6] [1,4] [2,5]. */
    static AssignmentStrategy roundRobin() {
        return (partitions, members) -> {
            Map<String, List<TopicPartition>> out = empty(members);
            for (int i = 0; i < partitions.size() && !members.isEmpty(); i++) {
                out.get(members.get(i % members.size())).add(partitions.get(i));
            }
            return out;
        };
    }

    private static Map<String, List<TopicPartition>> empty(List<String> members) {
        Map<String, List<TopicPartition>> out = new LinkedHashMap<>();
        members.forEach(m -> out.put(m, new ArrayList<>()));
        return out;
    }
}
