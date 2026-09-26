package com.lld.messaging.notification.core;

import com.lld.messaging.notification.channel.ChannelSender;
import com.lld.messaging.notification.channel.SendFailure;
import com.lld.messaging.notification.model.Category;
import com.lld.messaging.notification.model.Channel;
import com.lld.messaging.notification.model.Contact;
import com.lld.messaging.notification.model.Delivery;
import com.lld.messaging.notification.model.Notification;
import com.lld.messaging.notification.model.NotificationException;
import com.lld.messaging.notification.model.NotificationRequest;
import com.lld.messaging.notification.model.Priority;
import com.lld.messaging.notification.policy.Preferences;
import com.lld.messaging.notification.policy.RateLimiter;
import com.lld.messaging.notification.policy.RetryPolicy;
import com.lld.messaging.notification.template.RenderedMessage;
import com.lld.messaging.notification.template.Template;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Facade with two halves:
 * <ol>
 *   <li>{@link #send} (called by other services): validate, render every channel up front, apply
 *       preferences and quiet hours, and queue one {@link Delivery} per channel. Fast; no network.</li>
 *   <li>{@link #dispatchDue} (called by worker threads/a scheduler): take due deliveries, most urgent
 *       first, and hand them to channel senders, with rate limits, retries with backoff, and a fallback
 *       channel when one fails for good.</li>
 * </ol>
 *
 * <p>Concurrency: state changes happen under one lock; provider calls (slow network) happen
 * <em>outside</em> it. A delivery is <b>claimed</b> (PENDING to SENDING) under the lock, so two workers
 * never send the same delivery.
 */
public final class NotificationService {

    private final Object lock = new Object();
    private final Map<String, Contact> contacts = new HashMap<>();
    private final Map<String, Preferences> preferences = new HashMap<>();
    private final Map<String, Template> templates = new HashMap<>();
    private final Map<Channel, ChannelSender> senders = new EnumMap<>(Channel.class);
    private final Map<Channel, RateLimiter> rateLimits = new EnumMap<>(Channel.class);
    private final Map<Channel, Channel> fallbacks = new EnumMap<>(Channel.class);
    private final Map<String, Notification> byIdempotencyKey = new HashMap<>();
    private final Map<String, Notification> notifications = new HashMap<>();
    private final List<Delivery> pending = new ArrayList<>();
    private final List<DeliveryListener> listeners = new CopyOnWriteArrayList<>();
    private long notificationSeq;
    private long deliverySeq;

    private final RetryPolicy retry;
    private final Clock clock;

    public NotificationService(RetryPolicy retry, Clock clock) {
        this.retry = Objects.requireNonNull(retry);
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ configuration

    public void addListener(DeliveryListener listener) {
        listeners.add(listener);
    }

    public void registerSender(ChannelSender sender) {
        synchronized (lock) {
            senders.put(sender.channel(), sender);
        }
    }

    public void rateLimit(Channel channel, RateLimiter limiter) {
        synchronized (lock) {
            rateLimits.put(channel, limiter);
        }
    }

    /** If {@code from} fails for good and nothing reached the user yet, try {@code to}. */
    public void fallback(Channel from, Channel to) {
        synchronized (lock) {
            fallbacks.put(from, to);
        }
    }

    public void registerTemplate(Template template) {
        synchronized (lock) {
            templates.put(template.id(), template);
        }
    }

    public void registerUser(Contact contact) {
        synchronized (lock) {
            contacts.put(contact.userId(), contact);
            preferences.putIfAbsent(contact.userId(), Preferences.defaults());
        }
    }

    public Preferences preferences(String userId) {
        synchronized (lock) {
            requireContact(userId);
            return preferences.get(userId);
        }
    }

    // ------------------------------------------------------------------ intake

    /**
     * Accepts a request and queues its deliveries. Rejects the whole request (nothing queued) if the
     * user or template is unknown or a variable is missing. The same idempotency key returns the
     * notification created the first time.
     */
    public Notification send(NotificationRequest request) {
        List<Runnable> events = new ArrayList<>();
        Notification notification;
        synchronized (lock) {
            if (request.idempotencyKey() != null) {
                Notification existing = byIdempotencyKey.get(request.idempotencyKey());
                if (existing != null) {
                    return existing;
                }
            }
            Contact contact = requireContact(request.userId());
            Template template = templates.get(request.templateId());
            if (template == null) {
                throw new NotificationException("No template " + request.templateId());
            }
            Set<Channel> channels = request.channels() != null ? request.channels()
                    : preferences.get(contact.userId()).channelsFor(request.category());
            // render everything first: a broken template fails the request before anything is queued
            Map<Channel, Optional<RenderedMessage>> rendered = new EnumMap<>(Channel.class);
            for (Channel c : channels) {
                rendered.put(c, template.render(c, request.variables()));
            }

            Instant now = clock.instant();
            notification = new Notification("N" + (++notificationSeq), request, now);
            Instant start = holdForQuietHours(contact, request, now);
            for (Channel c : channels) {
                Delivery d = newDelivery(notification, contact, c, rendered.get(c).orElse(null), start);
                String skip = skipReason(contact, request.category(), c, rendered.get(c).isPresent());
                if (skip != null) {
                    d.skip(now, skip);
                    events.add(() -> listeners.forEach(l -> l.onSkipped(d)));
                } else {
                    if (start.isAfter(now)) {
                        d.postpone(now, start, "quiet hours");
                    }
                    pending.add(d);
                }
            }
            notifications.put(notification.id(), notification);
            if (request.idempotencyKey() != null) {
                byIdempotencyKey.put(request.idempotencyKey(), notification);
            }
        }
        events.forEach(Runnable::run);
        return notification;
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * One pass of the dispatcher: every due delivery, highest priority first, then oldest.
     * Safe to call from several worker threads at once.
     *
     * @return how many deliveries were attempted
     */
    public int dispatchDue() {
        List<Delivery> claimed = new ArrayList<>();
        List<Runnable> events = new ArrayList<>();
        synchronized (lock) {
            Instant now = clock.instant();
            List<Delivery> due = pending.stream().filter(d -> d.isDue(now))
                    .sorted(Comparator.comparing(Delivery::priority).reversed()
                            .thenComparing(Delivery::nextAttemptAt)
                            .thenComparingLong(Delivery::sequence))
                    .toList();
            for (Delivery d : due) {
                if (!admitByRateLimit(d, now, events)) {
                    continue;
                }
                d.claim();
                claimed.add(d);
                recordRateLimit(d, now);                  // reserve the slot now; a failure still counts
            }
        }
        events.forEach(Runnable::run);
        for (Delivery d : claimed) {
            attempt(d);
        }
        return claimed.size();
    }

    /** Runs {@link #dispatchDue} until nothing is due right now (demo/test helper). */
    public void drain() {
        while (dispatchDue() > 0) {
            // keep going: fallbacks may have queued new due deliveries
        }
    }

    private void attempt(Delivery d) {
        ChannelSender sender;
        synchronized (lock) {
            sender = senders.get(d.channel());
        }
        String reference = null;
        SendFailure failure = null;
        if (sender == null) {
            failure = SendFailure.permanent("no sender registered for " + d.channel());
        } else {
            try {
                reference = sender.send(d.contact(), d.message());          // network call, outside the lock
            } catch (SendFailure f) {
                failure = f;
            }
        }
        List<Runnable> events = new ArrayList<>();
        synchronized (lock) {
            Instant now = clock.instant();
            if (failure == null) {
                d.sent(now, reference);
                pending.remove(d);
                events.add(() -> listeners.forEach(l -> l.onSent(d)));
            } else if (failure.permanent() || !retry.canRetry(d.attempts())) {
                d.fail(now, failure.getMessage());
                pending.remove(d);
                events.add(() -> listeners.forEach(l -> l.onFailed(d)));
                queueFallback(d, now, events);
            } else {
                d.retryAt(now, now.plus(retry.delayAfter(d.attempts())), failure.getMessage());
            }
        }
        events.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ rules

    private String skipReason(Contact contact, Category category, Channel channel, boolean hasTemplate) {
        if (!preferences.get(contact.userId()).allows(category, channel)) {
            return "user opted out of " + category + " on " + channel;
        }
        if (!channel.reachable(contact)) {
            return "no " + channel + " contact for " + contact.name();
        }
        if (!hasTemplate) {
            return "template has no " + channel + " version";
        }
        return null;
    }

    /** Security and HIGH priority ignore quiet hours; the rest waits until the morning. */
    private Instant holdForQuietHours(Contact contact, NotificationRequest request, Instant now) {
        if (request.category() == Category.SECURITY || request.priority() == Priority.HIGH) {
            return now;
        }
        return preferences.get(contact.userId()).quietUntil(now);
    }

    /** Marketing over the limit is dropped; anything else waits for the next free slot. Security is never limited. */
    private boolean admitByRateLimit(Delivery d, Instant now, List<Runnable> events) {
        RateLimiter limiter = rateLimits.get(d.channel());
        if (limiter == null || d.category() == Category.SECURITY) {
            return true;
        }
        Instant allowed = limiter.allowedAt(rateKey(d), now);
        if (!allowed.isAfter(now)) {
            return true;
        }
        if (d.category() == Category.MARKETING) {
            d.skip(now, "rate limit reached for " + d.channel());
            pending.remove(d);
            events.add(() -> listeners.forEach(l -> l.onSkipped(d)));
        } else {
            d.postpone(now, allowed, "rate limit reached for " + d.channel());
        }
        return false;
    }

    private void recordRateLimit(Delivery d, Instant now) {
        RateLimiter limiter = rateLimits.get(d.channel());
        if (limiter != null && d.category() != Category.SECURITY) {
            limiter.record(rateKey(d), now);
        }
    }

    private void queueFallback(Delivery failed, Instant now, List<Runnable> events) {
        Channel next = fallbacks.get(failed.channel());
        Notification n = notifications.get(failed.notificationId());
        if (next == null || n.delivered()
                || n.deliveries().stream().anyMatch(d -> d.channel() == next)) {
            return;
        }
        NotificationRequest r = n.request();
        Template template = templates.get(r.templateId());
        Optional<RenderedMessage> message;
        try {
            message = template.render(next, r.variables());
        } catch (NotificationException e) {
            message = Optional.empty();
        }
        Delivery d = newDelivery(n, failed.contact(), next, message.orElse(null), now);
        String skip = skipReason(failed.contact(), r.category(), next, message.isPresent());
        if (skip != null) {
            d.skip(now, "fallback: " + skip);
            events.add(() -> listeners.forEach(l -> l.onSkipped(d)));
        } else {
            pending.add(d);
        }
    }

    private Delivery newDelivery(Notification n, Contact contact, Channel channel, RenderedMessage message, Instant at) {
        Delivery d = new Delivery(n.id() + "-" + channel.name().toLowerCase(), n.id(), contact, channel,
                n.request().category(), n.request().priority(), message, at, ++deliverySeq);
        n.add(d);
        return d;
    }

    // ------------------------------------------------------------------ queries

    public Notification notification(String id) {
        synchronized (lock) {
            Notification n = notifications.get(id);
            if (n == null) {
                throw new NotificationException("No notification " + id);
            }
            return n;
        }
    }

    public List<Delivery> pendingDeliveries() {
        synchronized (lock) {
            return pending.stream().sorted(Comparator.comparingLong(Delivery::sequence)).toList();
        }
    }

    /** Runs {@code action} for each notification in creation order (reporting). */
    public void forEachNotification(Consumer<Notification> action) {
        List<Notification> copy;
        synchronized (lock) {
            copy = notifications.values().stream().sorted(Comparator.comparing(n -> Long.parseLong(n.id().substring(1)))).toList();
        }
        copy.forEach(action);
    }

    private Contact requireContact(String userId) {
        Contact c = contacts.get(userId);
        if (c == null) {
            throw new NotificationException("No user " + userId);
        }
        return c;
    }

    private static String rateKey(Delivery d) {
        return d.contact().userId() + "|" + d.channel();
    }
}
