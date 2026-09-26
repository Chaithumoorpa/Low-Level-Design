package com.lld.messaging.pubsub;

import com.lld.messaging.pubsub.broker.Broker;
import com.lld.messaging.pubsub.broker.ManualClock;
import com.lld.messaging.pubsub.broker.Partitioner;
import com.lld.messaging.pubsub.broker.RetentionPolicy;
import com.lld.messaging.pubsub.consumer.AssignmentStrategy;
import com.lld.messaging.pubsub.consumer.Consumer;
import com.lld.messaging.pubsub.consumer.ConsumerGroup;
import com.lld.messaging.pubsub.consumer.PushSubscription;
import com.lld.messaging.pubsub.model.Message;
import com.lld.messaging.pubsub.model.PubSubException;
import com.lld.messaging.pubsub.model.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PubSubTest {

    private ManualClock clock;
    private Broker broker;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-12-01T09:00:00Z"));
        broker = new Broker(clock);
        broker.createTopic("t", 4, RetentionPolicy.keepForever());
    }

    private static TopicPartition tp(int p) {
        return new TopicPartition("t", p);
    }

    private List<Message> drain(Consumer c) {
        List<Message> all = new ArrayList<>();
        List<Message> batch;
        while (!(batch = c.poll(7)).isEmpty()) {
            all.addAll(batch);
        }
        return all;
    }

    // ------------------------------------------------------------------ publishing

    @Nested
    class Publishing {

        @Test
        void offsetsAreConsecutivePerPartitionAndKeysStick() {
            Map<String, Integer> partitionOfKey = new HashMap<>();
            Map<Integer, Long> expectedNext = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                String key = "k" + (i % 10);
                Message m = broker.publish("t", key, "v" + i);
                assertEquals(partitionOfKey.computeIfAbsent(key, k -> m.partition()), m.partition());
                assertEquals(expectedNext.getOrDefault(m.partition(), 0L), m.offset());
                expectedNext.put(m.partition(), m.offset() + 1);
            }
        }

        @Test
        void keylessMessagesAreSpreadRoundRobin() {
            List<Integer> partitions = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                partitions.add(broker.publish("t", null, "x").partition());
            }
            assertEquals(List.of(0, 1, 2, 3, 0, 1, 2, 3), partitions);
        }

        @Test
        void perKeyOrderIsPreservedForTheReader() {
            for (int i = 0; i < 50; i++) {
                broker.publish("t", "order-" + (i % 5), String.valueOf(i));
            }
            Map<String, Integer> last = new HashMap<>();
            for (Message m : drain(broker.group("g", "t").join("c"))) {
                int v = Integer.parseInt(m.value());
                assertTrue(v > last.getOrDefault(m.key(), -1), "out of order for " + m.key());
                last.put(m.key(), v);
            }
        }

        @Test
        void topicErrors() {
            assertThrows(PubSubException.class, () -> broker.publish("nope", "k", "v"));
            assertThrows(PubSubException.class, () -> broker.createTopic("t", 2, RetentionPolicy.keepForever()));
            assertThrows(IllegalArgumentException.class, () -> broker.createTopic("z", 0, RetentionPolicy.keepForever()));
            assertThrows(PubSubException.class, () -> broker.fetch(tp(9), 0, 1));
            broker.createTopic("other", 1, RetentionPolicy.keepForever());
            broker.group("g", "t");
            assertThrows(PubSubException.class, () -> broker.group("g", "other"), "a group reads one topic");
        }

        @Test
        void customPartitionerIsUsed() {
            broker.createTopic("fixed", 3, RetentionPolicy.keepForever(), (key, n) -> 2);
            assertEquals(2, broker.publish("fixed", "a", "v").partition());
            assertEquals(2, broker.publish("fixed", null, "v").partition());
            assertEquals(Math.floorMod("abc".hashCode(), 5), Partitioner.keyHashOrRoundRobin().partition("abc", 5));
        }
    }

    // ------------------------------------------------------------------ groups

    @Nested
    class Groups {

        @Test
        void everyGroupGetsEveryMessage() {
            for (int i = 0; i < 20; i++) {
                broker.publish("t", "k" + i, "v");
            }
            assertEquals(20, drain(broker.group("a", "t").join("a1")).size());
            assertEquals(20, drain(broker.group("b", "t").join("b1")).size());
        }

        @Test
        void insideAGroupEachPartitionHasOneOwner() {
            ConsumerGroup g = broker.group("g", "t");
            Consumer c1 = g.join("c1");
            assertEquals(List.of(tp(0), tp(1), tp(2), tp(3)), c1.assignment());
            Consumer c2 = g.join("c2");
            Consumer c3 = g.join("c3");
            assertEquals(Map.of("c1", List.of(tp(0), tp(1)), "c2", List.of(tp(2)), "c3", List.of(tp(3))), g.assignments());
            g.join("c4");
            Consumer c5 = g.join("c5");
            assertEquals(List.of(), c5.assignment(), "more members than partitions: one sits idle");
            assertEquals(5, g.generation());
            c2.close();
            c3.close();
            Set<TopicPartition> covered = new HashSet<>();
            g.assignments().values().forEach(covered::addAll);
            assertEquals(4, covered.size());
        }

        @Test
        void roundRobinDealsPartitions() {
            List<TopicPartition> seven = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                seven.add(new TopicPartition("x", i));
            }
            Map<String, List<TopicPartition>> rr = AssignmentStrategy.roundRobin().assign(seven, List.of("a", "b", "c"));
            assertEquals(List.of(seven.get(0), seven.get(3), seven.get(6)), rr.get("a"));
            Map<String, List<TopicPartition>> range = AssignmentStrategy.range().assign(seven, List.of("a", "b", "c"));
            assertEquals(seven.subList(0, 3), range.get("a"));
            assertEquals(seven.subList(5, 7), range.get("c"));
            assertEquals(Map.of("a", List.of()), AssignmentStrategy.range().assign(List.of(), List.of("a")));
        }

        @Test
        void uncommittedMessagesAreRedeliveredAfterARebalance() {
            for (int i = 0; i < 12; i++) {
                broker.publish("t", null, "m" + i);
            }
            ConsumerGroup g = broker.group("g", "t");
            Consumer c1 = g.join("c1");
            List<Message> first = c1.poll(6);
            c1.commit(first.get(2));                          // only part of what was read
            c1.close();                                        // "crash"
            Consumer c2 = g.join("c2");
            List<Message> redelivered = drain(c2);
            assertEquals(12, redelivered.size() + countCommitted(g), "nothing lost");
            assertTrue(redelivered.stream().anyMatch(m -> m.equals(first.get(3)) || m.equals(first.get(4))),
                    "read but uncommitted messages come back");
        }

        private long countCommitted(ConsumerGroup g) {
            long n = 0;
            for (int p = 0; p < 4; p++) {
                n += Math.max(0, g.committed(tp(p)));
            }
            return n;
        }

        @Test
        void committedProgressSurvivesMembership() {
            for (int i = 0; i < 10; i++) {
                broker.publish("t", null, "m" + i);
            }
            ConsumerGroup g = broker.group("g", "t");
            Consumer c1 = g.join("c1");
            drain(c1);
            c1.commit();
            assertEquals(0, g.lag());
            c1.close();
            broker.publish("t", null, "new");
            assertEquals(1, g.lag());
            assertEquals(List.of("new"), drain(g.join("c2")).stream().map(Message::value).toList());
        }

        @Test
        void zombiesAndStaleOwnersAreFenced() {
            broker.publish("t", null, "a");
            broker.publish("t", null, "b");
            ConsumerGroup g = broker.group("g", "t");
            Consumer c1 = g.join("c1");
            List<Message> read = drain(c1);
            Consumer c2 = g.join("c2");                         // c1 keeps t-0,t-1; c2 gets t-2,t-3
            Message onP1 = read.stream().filter(m -> m.partition() == 1).findFirst().orElseThrow();
            c1.commit(onP1);                                   // still owns t-1: fine
            Message moved = new Message("t", 2, 0, null, "x", Map.of(), clock.instant());
            assertThrows(PubSubException.class, () -> c1.commit(moved), "t-2 moved to c2");
            g.expire("c1");
            assertThrows(PubSubException.class, c1::commit);
            assertThrows(PubSubException.class, () -> c1.poll(1));
            assertEquals(4, c2.assignment().size());
            assertThrows(PubSubException.class, () -> g.expire("c1"));
            assertThrows(PubSubException.class, () -> g.join("c2"));
        }

        @Test
        void latestResetSkipsHistory() {
            broker.publish("t", "k", "old");
            Consumer c = broker.group("late", "t", AssignmentStrategy.range(), ConsumerGroup.OffsetReset.LATEST).join("c");
            broker.publish("t", "k", "new");
            assertEquals(List.of("new"), drain(c).stream().map(Message::value).toList());
        }

        @Test
        void pollIsFairAcrossPartitions() {
            for (int i = 0; i < 40; i++) {
                broker.publish("t", null, "m");
            }
            Consumer c = broker.group("g", "t").join("c");
            Set<Integer> firstPartitions = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                firstPartitions.add(c.poll(3).get(0).partition());
            }
            assertEquals(Set.of(0, 1, 2, 3), firstPartitions, "each poll starts at a different partition");
        }
    }

    // ------------------------------------------------------------------ retention

    @Test
    void retentionByCountAndAgeMovesTheStartOffset() {
        broker.createTopic("r", 1, new RetentionPolicy(5, Duration.ofHours(1)));
        TopicPartition r0 = new TopicPartition("r", 0);
        for (int i = 0; i < 8; i++) {
            broker.publish("r", null, "m" + i);
        }
        assertEquals(3, broker.enforceRetention());
        assertEquals(3, broker.startOffset(r0));
        assertEquals(8, broker.endOffset(r0));
        assertEquals("m3", broker.fetch(r0, 0, 1).get(0).value(), "reading below the start is clamped");
        clock.advance(Duration.ofMinutes(61));
        broker.publish("r", null, "fresh");
        assertEquals(5, broker.enforceRetention());
        assertEquals(List.of("fresh"), broker.fetch(r0, 0, 10).stream().map(Message::value).toList());
        assertEquals(8, broker.fetch(r0, 0, 10).get(0).offset(), "offsets are never reused");
    }

    // ------------------------------------------------------------------ push subscription

    @Test
    void pushSubscriptionRetriesThenDeadLettersAndFilters() {
        broker.publish("t", "a", "ok-1");
        broker.publish("t", "a", "poison");
        broker.publish("t", "a", "flaky");
        broker.publish("t", "a", "skip-me");
        broker.publish("t", "a", "ok-2");
        AtomicInteger flakyCalls = new AtomicInteger();
        List<String> handled = new ArrayList<>();
        PushSubscription sub = new PushSubscription(broker, broker.group("g", "t").join("c"), m -> {
            if (m.value().equals("poison")) {
                throw new IllegalStateException("bad payload");
            }
            if (m.value().equals("flaky") && flakyCalls.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary");
            }
            handled.add(m.value());
        }, m -> !m.value().startsWith("skip"), 3, "t.dlq");
        sub.drain();
        assertEquals(List.of("ok-1", "flaky", "ok-2"), handled, "order kept, poison didn't block");
        assertEquals(3, sub.delivered());
        assertEquals(1, sub.filtered());
        assertEquals(1, sub.deadLettered());
        Message dead = broker.fetch(new TopicPartition("t.dlq", 0), 0, 10).get(0);
        assertEquals("poison", dead.value());
        assertEquals("bad payload", dead.headers().get("dlq.error"));
        assertEquals(0, broker.group("g", "t").lag());
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentPublishersNeverShareAnOffset() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Message>> futures = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String key = "k" + (i % 3);
            futures.add(pool.submit(() -> {
                start.await();
                return broker.publish("t", key, "v");
            }));
        }
        start.countDown();
        Set<String> positions = new HashSet<>();
        for (Future<Message> f : futures) {
            Message m = f.get(10, TimeUnit.SECONDS);
            assertTrue(positions.add(m.partition() + "@" + m.offset()));
        }
        pool.shutdown();
        long total = 0;
        for (int p = 0; p < 4; p++) {
            total += broker.endOffset(tp(p));
        }
        assertEquals(2000, total, "no gaps");
    }

    @Test
    void groupMembersOnThreadsConsumeEverythingExactlyOnceWithoutRebalances() throws Exception {
        for (int i = 0; i < 1000; i++) {
            broker.publish("t", null, String.valueOf(i));
        }
        ConsumerGroup g = broker.group("g", "t");
        List<Consumer> members = List.of(g.join("c1"), g.join("c2"), g.join("c3"), g.join("c4"));
        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicBoolean duplicate = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        for (Consumer c : members) {
            futures.add(pool.submit(() -> {
                List<Message> batch;
                while (!(batch = c.poll(13)).isEmpty()) {
                    for (Message m : batch) {
                        if (!seen.add(m.topicPartition() + "@" + m.offset())) {
                            duplicate.set(true);
                        }
                    }
                    c.commit();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1000, seen.size());
        assertTrue(!duplicate.get());
        assertEquals(0, g.lag());
    }
}
