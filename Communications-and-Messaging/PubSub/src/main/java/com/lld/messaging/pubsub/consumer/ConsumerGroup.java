package com.lld.messaging.pubsub.consumer;

import com.lld.messaging.pubsub.broker.Broker;
import com.lld.messaging.pubsub.model.Message;
import com.lld.messaging.pubsub.model.PubSubException;
import com.lld.messaging.pubsub.model.TopicPartition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Subscribers that share the work of one topic. Between groups: <b>fan-out</b> (each group gets every
 * message). Inside a group: <b>load balancing</b> (each partition is read by exactly one member).
 *
 * <p>The group remembers one <b>committed offset</b> per partition: "everything before this is done".
 * When membership changes it <b>rebalances</b>: bumps the generation, reassigns partitions, and new
 * owners resume from the committed offsets. Messages read but not committed are read again
 * (<b>at-least-once</b>). Commits from members that are no longer in the group, or for partitions they
 * no longer own, are rejected (<b>fencing</b>) so a slow "zombie" can't overwrite progress.
 *
 * <p>All group state is guarded by the group's monitor.
 */
public final class ConsumerGroup {

    /** Where a group with no committed offset starts reading. */
    public enum OffsetReset {
        EARLIEST, LATEST
    }

    private final Broker broker;
    private final String groupId;
    private final String topic;
    private final AssignmentStrategy strategy;
    private final OffsetReset reset;
    private final Map<String, Consumer> members = new LinkedHashMap<>();
    private final Map<TopicPartition, Long> committed = new HashMap<>();
    private int generation;

    public ConsumerGroup(Broker broker, String groupId, String topic, AssignmentStrategy strategy, OffsetReset reset) {
        this.broker = broker;
        this.groupId = groupId;
        this.topic = topic;
        this.strategy = strategy;
        this.reset = reset;
    }

    public String groupId() {
        return groupId;
    }

    public String topic() {
        return topic;
    }

    // ------------------------------------------------------------------ membership

    public synchronized Consumer join(String memberId) {
        if (members.containsKey(memberId)) {
            throw new PubSubException(memberId + " is already in group " + groupId);
        }
        Consumer c = new Consumer(this, memberId);
        members.put(memberId, c);
        rebalance();
        return c;
    }

    synchronized void leave(Consumer c) {
        if (members.remove(c.id()) != null) {
            c.closed = true;
            rebalance();
        }
    }

    /** The coordinator stopped hearing heartbeats from a member: evict it and rebalance. */
    public synchronized void expire(String memberId) {
        Consumer c = members.remove(memberId);
        if (c == null) {
            throw new PubSubException("No member " + memberId);
        }
        c.closed = true;
        rebalance();
    }

    private void rebalance() {
        generation++;
        List<String> ids = members.keySet().stream().sorted().toList();
        Map<String, List<TopicPartition>> plan = strategy.assign(broker.partitions(topic), ids);
        for (Consumer c : members.values()) {
            List<TopicPartition> mine = plan.getOrDefault(c.id(), List.of());
            Map<TopicPartition, Long> positions = new LinkedHashMap<>();
            for (TopicPartition tp : mine) {
                positions.put(tp, startingOffset(tp));
            }
            c.generation = generation;
            c.assignment = List.copyOf(mine);
            c.positions = positions;
            c.nextPartition = 0;
        }
    }

    private long startingOffset(TopicPartition tp) {
        Long done = committed.get(tp);
        if (done != null) {
            return Math.max(done, broker.startOffset(tp));
        }
        return reset == OffsetReset.EARLIEST ? broker.startOffset(tp) : broker.endOffset(tp);
    }

    // ------------------------------------------------------------------ reading & committing

    synchronized List<Message> poll(Consumer c, int max) {
        requireMember(c);
        List<Message> out = new ArrayList<>();
        int n = c.assignment.size();
        for (int i = 0; i < n && out.size() < max; i++) {
            TopicPartition tp = c.assignment.get((c.nextPartition + i) % n);
            long position = Math.max(c.positions.get(tp), broker.startOffset(tp));   // retention may have moved on
            List<Message> batch = broker.fetch(tp, position, max - out.size());
            out.addAll(batch);
            c.positions.put(tp, position + batch.size());
        }
        if (n > 0) {
            c.nextPartition = (c.nextPartition + 1) % n;                             // fairness across partitions
        }
        return out;
    }

    synchronized void commit(Consumer c, int memberGeneration, Map<TopicPartition, Long> offsets) {
        requireMember(c);
        if (memberGeneration != generation) {
            throw new PubSubException(c.id() + " committed with generation " + memberGeneration
                    + " but the group is at " + generation + "; poll again");
        }
        for (Map.Entry<TopicPartition, Long> e : offsets.entrySet()) {
            if (!c.assignment.contains(e.getKey())) {
                throw new PubSubException(c.id() + " no longer owns " + e.getKey());
            }
        }
        committed.putAll(offsets);
    }

    private void requireMember(Consumer c) {
        if (c.closed || members.get(c.id()) != c) {
            throw new PubSubException(c.id() + " is not a member of group " + groupId + " any more");
        }
    }

    // ------------------------------------------------------------------ queries

    /** @return committed offset, or -1 if nothing committed yet */
    public synchronized long committed(TopicPartition tp) {
        return committed.getOrDefault(tp, -1L);
    }

    /** Messages published but not yet committed by this group, over all partitions. */
    public synchronized long lag() {
        long lag = 0;
        for (TopicPartition tp : broker.partitions(topic)) {
            lag += broker.endOffset(tp) - Math.max(committed.getOrDefault(tp, broker.startOffset(tp)), broker.startOffset(tp));
        }
        return lag;
    }

    public synchronized Map<String, List<TopicPartition>> assignments() {
        Map<String, List<TopicPartition>> out = new TreeMap<>();
        members.values().forEach(c -> out.put(c.id(), c.assignment));
        return out;
    }

    public synchronized int generation() {
        return generation;
    }

    synchronized List<TopicPartition> assignmentOf(Consumer c) {
        return c.assignment;
    }

    synchronized int generationOf(Consumer c) {
        return c.generation;
    }

    synchronized Map<TopicPartition, Long> positionsOf(Consumer c) {
        return new LinkedHashMap<>(c.positions);
    }
}
