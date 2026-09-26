package com.lld.messaging.pubsub;

import com.lld.messaging.pubsub.broker.Broker;
import com.lld.messaging.pubsub.broker.ManualClock;
import com.lld.messaging.pubsub.broker.RetentionPolicy;
import com.lld.messaging.pubsub.consumer.AssignmentStrategy;
import com.lld.messaging.pubsub.consumer.Consumer;
import com.lld.messaging.pubsub.consumer.ConsumerGroup;
import com.lld.messaging.pubsub.consumer.PushSubscription;
import com.lld.messaging.pubsub.model.Message;
import com.lld.messaging.pubsub.model.PubSubException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** Order events flowing to three independent services, on a simulated clock. */
public class PubSubApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2026-12-01T09:00:00Z"));
        Broker broker = new Broker(clock);
        broker.createTopic("orders", 3, new RetentionPolicy(1000, Duration.ofDays(7)));

        step("Publish: the key (order id) picks the partition, so each order's events stay in order");
        String[][] events = {
                {"o-1", "created"}, {"o-2", "created"}, {"o-1", "paid"}, {"o-3", "created"},
                {"o-2", "paid"}, {"o-1", "shipped"}, {"o-3", "cancelled"}, {"o-4", "created"}};
        for (String[] e : events) {
            System.out.println("   " + broker.publish("orders", e[0], e[1]));
        }

        step("Fan-out: 'billing' and 'email' are different groups, so both see every event");
        ConsumerGroup billing = broker.group("billing", "orders");
        ConsumerGroup emailGroup = broker.group("email", "orders", AssignmentStrategy.roundRobin(), ConsumerGroup.OffsetReset.EARLIEST);
        Consumer billing1 = billing.join("billing-1");
        Consumer email1 = emailGroup.join("email-1");
        System.out.println("   billing-1 read " + billing1.poll(100).size() + ", email-1 read " + email1.poll(100).size());
        email1.commit();

        step("Load balancing inside a group: a second billing instance joins, partitions are split");
        Consumer billing2 = billing.join("billing-2");
        System.out.println("   generation " + billing.generation() + " assignments " + billing.assignments());

        step("At-least-once: billing-1 processed but crashed before committing");
        List<Message> again = billing1.poll(100);
        System.out.println("   billing-1 re-reads from the committed offset: " + again.size() + " messages "
                + again.stream().map(m -> m.topicPartition() + "@" + m.offset()).collect(Collectors.joining(" ")));
        billing.expire("billing-1");
        System.out.println("   coordinator evicts billing-1 (missed heartbeats): " + billing.assignments());
        try {
            billing1.commit();
        } catch (PubSubException e) {
            System.out.println("   zombie billing-1 tries to commit: [rejected] " + e.getMessage());
        }
        List<Message> taken = billing2.poll(100);
        billing2.commit();
        System.out.println("   billing-2 now owns everything, processes " + taken.size() + " and commits; lag = " + billing.lag());

        step("Push subscription with a poison message and a dead-letter topic");
        broker.publish("orders", "o-5", "created");
        broker.publish("orders", "o-6", "created{broken json");
        broker.publish("orders", "o-5", "paid");
        ConsumerGroup analytics = broker.group("analytics", "orders", AssignmentStrategy.range(), ConsumerGroup.OffsetReset.LATEST);
        Consumer a1 = analytics.join("analytics-1");
        broker.publish("orders", "o-7", "created");
        broker.publish("orders", "o-7", "{broken");
        broker.publish("orders", "o-8", "created");
        broker.publish("orders", "o-9", "shipped");
        PushSubscription sub = new PushSubscription(broker, a1, m -> {
            if (m.value().contains("{")) {
                throw new IllegalArgumentException("cannot parse '" + m.value() + "'");
            }
            System.out.println("   analytics handled " + m);
        }, m -> !m.value().equals("shipped"), 3, "orders.dlq");
        sub.drain();
        System.out.println("   delivered " + sub.delivered() + ", filtered " + sub.filtered() + ", dead-lettered "
                + sub.deadLettered() + " (analytics started at LATEST, so o-5/o-6 are not its business)");
        broker.group("dlq-inspector", "orders.dlq").join("ops").poll(10)
                .forEach(m -> System.out.println("   DLQ: " + m + " " + m.headers()));

        step("Retention: 8 days later old messages are gone; a slow group jumps to the oldest kept offset");
        clock.advance(Duration.ofDays(8));
        broker.publish("orders", "o-10", "created");
        System.out.println("   dropped " + broker.enforceRetention() + " messages");
        System.out.println("   email-1 never read o-5..o-9 before they expired; it resumes at the oldest kept offset: " + email1.poll(100));
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }
}
