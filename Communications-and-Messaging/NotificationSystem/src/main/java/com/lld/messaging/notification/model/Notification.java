package com.lld.messaging.notification.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One accepted request and its deliveries (one per channel, plus any fallbacks). */
public final class Notification {

    private final String id;
    private final NotificationRequest request;
    private final Instant createdAt;
    private final List<Delivery> deliveries = new ArrayList<>();

    public Notification(String id, NotificationRequest request, Instant createdAt) {
        this.id = id;
        this.request = request;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public NotificationRequest request() {
        return request;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    public synchronized void add(Delivery d) {
        deliveries.add(d);
    }

    /** Reached the user on at least one channel. */
    public synchronized boolean delivered() {
        return deliveries.stream().anyMatch(d -> d.status() == Delivery.Status.SENT);
    }

    @Override
    public String toString() {
        return id + " " + request.templateId() + " -> " + deliveries();
    }
}
