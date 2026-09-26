package com.lld.messaging.notification.model;

import com.lld.messaging.notification.template.RenderedMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One notification on one channel ("the order-shipped e-mail to Asha"). Retries, rate limits and
 * quiet hours all act on deliveries, so one failing channel never holds back the others.
 *
 * <pre>
 * PENDING --claimed by a dispatcher--> SENDING --ok--> SENT
 *                                        |--temporary error--> PENDING (later)
 *                                        |--permanent error / out of attempts--> FAILED
 * PENDING --opted out, unreachable, marketing over limit--> SKIPPED
 * </pre>
 */
public final class Delivery {

    public enum Status {
        PENDING, SENDING, SENT, FAILED, SKIPPED
    }

    private final String id;
    private final String notificationId;
    private final Contact contact;
    private final Channel channel;
    private final Category category;
    private final Priority priority;
    private final RenderedMessage message;
    private final long sequence;
    private final List<String> log = new ArrayList<>();
    private Status status = Status.PENDING;
    private Instant nextAttemptAt;
    private int attempts;

    public Delivery(String id, String notificationId, Contact contact, Channel channel, Category category,
                    Priority priority, RenderedMessage message, Instant firstAttemptAt, long sequence) {
        this.id = id;
        this.notificationId = notificationId;
        this.contact = contact;
        this.channel = channel;
        this.category = category;
        this.priority = priority;
        this.message = message;
        this.nextAttemptAt = firstAttemptAt;
        this.sequence = sequence;
    }

    public String id() {
        return id;
    }

    public String notificationId() {
        return notificationId;
    }

    public Contact contact() {
        return contact;
    }

    public Channel channel() {
        return channel;
    }

    public Category category() {
        return category;
    }

    public Priority priority() {
        return priority;
    }

    public RenderedMessage message() {
        return message;
    }

    /** Creation order, used as the final tie-breaker so dispatch order is deterministic. */
    public long sequence() {
        return sequence;
    }

    public Status status() {
        return status;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public int attempts() {
        return attempts;
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    public boolean isDue(Instant now) {
        return status == Status.PENDING && !nextAttemptAt.isAfter(now);
    }

    // ---- transitions (called by the service under its lock)

    public void claim() {
        status = Status.SENDING;
        attempts++;
    }

    public void sent(Instant at, String note) {
        status = Status.SENT;
        log.add(at + " attempt " + attempts + " sent" + (note.isEmpty() ? "" : " (" + note + ")"));
    }

    public void retryAt(Instant at, Instant when, String error) {
        status = Status.PENDING;
        nextAttemptAt = when;
        log.add(at + " attempt " + attempts + " failed: " + error + ", retry at " + when);
    }

    public void postpone(Instant at, Instant when, String reason) {
        if (status == Status.SENDING) {
            attempts--;                                   // not a real attempt
        }
        status = Status.PENDING;
        nextAttemptAt = when;
        log.add(at + " postponed to " + when + ": " + reason);
    }

    public void fail(Instant at, String error) {
        status = Status.FAILED;
        log.add(at + " attempt " + attempts + " failed for good: " + error);
    }

    public void skip(Instant at, String reason) {
        if (status == Status.SENDING) {
            attempts--;
        }
        status = Status.SKIPPED;
        log.add(at + " skipped: " + reason);
    }

    @Override
    public String toString() {
        return id + " " + channel + " to " + contact.name() + " [" + status + "]";
    }
}
