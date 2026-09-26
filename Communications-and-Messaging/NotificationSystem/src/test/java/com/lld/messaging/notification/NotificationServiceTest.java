package com.lld.messaging.notification;

import com.lld.messaging.notification.channel.FakeSender;
import com.lld.messaging.notification.channel.SendFailure;
import com.lld.messaging.notification.core.ManualClock;
import com.lld.messaging.notification.core.NotificationService;
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
import com.lld.messaging.notification.template.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationServiceTest {

    private ManualClock clock;
    private NotificationService service;
    private FakeSender email;
    private FakeSender sms;
    private FakeSender push;
    private FakeSender inApp;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-11-02T12:00:00Z"));
        service = new NotificationService(new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(25)), clock);
        email = new FakeSender(Channel.EMAIL);
        sms = new FakeSender(Channel.SMS);
        push = new FakeSender(Channel.PUSH);
        inApp = new FakeSender(Channel.IN_APP);
        List.of(email, sms, push, inApp).forEach(service::registerSender);
        service.registerTemplate(Template.named("t")
                .channel(Channel.EMAIL, "Hi {name}", "Body for {name}")
                .channel(Channel.SMS, null, "SMS for {name}")
                .channel(Channel.PUSH, "Push", "Push for {name}")
                .channel(Channel.IN_APP, "", "In-app for {name}"));
        service.registerTemplate(Template.named("email-only").channel(Channel.EMAIL, "s", "b"));
        service.registerUser(new Contact("u1", "Asha", "a@x.com", "+1", "tok"));
        service.registerUser(new Contact("u2", "Ben", "b@x.com", null, null));
    }

    private NotificationRequest.Builder req(String user, Category category) {
        return NotificationRequest.to(user).template("t").with("name", user).category(category);
    }

    private static Map<Channel, Delivery.Status> statuses(Notification n) {
        Map<Channel, Delivery.Status> m = new java.util.EnumMap<>(Channel.class);
        n.deliveries().forEach(d -> m.put(d.channel(), d.status()));
        return m;
    }

    // ------------------------------------------------------------------ intake

    @Nested
    class Intake {

        @Test
        void usesPreferencesPerCategory() {
            Notification n = service.send(req("u1", Category.TRANSACTIONAL).build());
            assertEquals(Set.of(Channel.EMAIL, Channel.PUSH, Channel.IN_APP), statuses(n).keySet());
            service.drain();
            assertEquals(Map.of(Channel.EMAIL, Delivery.Status.SENT, Channel.PUSH, Delivery.Status.SENT,
                    Channel.IN_APP, Delivery.Status.SENT), statuses(n));
            assertEquals(List.of("u1: [Hi u1] Body for u1"), email.outbox());
        }

        @Test
        void unreachableOptedOutAndMissingTemplateChannelsAreSkipped() {
            service.preferences("u1").setChannels(Category.TRANSACTIONAL, EnumSet.of(Channel.EMAIL));
            Notification a = service.send(req("u1", Category.TRANSACTIONAL).only(Channel.SMS, Channel.EMAIL).build());
            assertEquals(Delivery.Status.SKIPPED, statuses(a).get(Channel.SMS), "opted out");
            Notification b = service.send(req("u2", Category.SECURITY).only(Channel.SMS).build());
            assertTrue(b.deliveries().get(0).log().get(0).contains("no SMS contact"));
            Notification c = service.send(NotificationRequest.to("u1").template("email-only")
                    .category(Category.SECURITY).only(Channel.EMAIL, Channel.SMS).build());
            assertEquals(Delivery.Status.SKIPPED, statuses(c).get(Channel.SMS), "template has no SMS version");
        }

        @Test
        void badRequestsQueueNothing() {
            assertThrows(NotificationException.class, () -> service.send(req("nobody", Category.MARKETING).build()));
            assertThrows(NotificationException.class, () -> service.send(NotificationRequest.to("u1").template("nope")
                    .category(Category.MARKETING).build()));
            assertThrows(NotificationException.class, () -> service.send(NotificationRequest.to("u1").template("t")
                    .category(Category.TRANSACTIONAL).build()), "missing {name}");
            assertEquals(List.of(), service.pendingDeliveries());
        }

        @Test
        void longSmsIsRejected() {
            service.registerTemplate(Template.named("long").channel(Channel.SMS, null, "x".repeat(161)));
            assertThrows(NotificationException.class, () -> service.send(NotificationRequest.to("u1").template("long")
                    .category(Category.SECURITY).only(Channel.SMS).build()));
        }

        @Test
        void idempotencyKeyReturnsTheFirstNotification() {
            Notification first = service.send(req("u1", Category.TRANSACTIONAL).idempotencyKey("k").build());
            assertSame(first, service.send(req("u1", Category.TRANSACTIONAL).idempotencyKey("k").build()));
            service.drain();
            assertEquals(1, email.outbox().size());
        }

        @Test
        void securityAlertsCantBeTurnedOffAndIgnoreOptOuts() {
            assertThrows(NotificationException.class,
                    () -> service.preferences("u1").setChannels(Category.SECURITY, EnumSet.noneOf(Channel.class)));
            service.preferences("u1").setChannels(Category.SECURITY, EnumSet.of(Channel.EMAIL));
            Notification n = service.send(req("u1", Category.SECURITY).only(Channel.SMS).build());
            service.drain();
            assertEquals(Delivery.Status.SENT, statuses(n).get(Channel.SMS));
        }
    }

    // ------------------------------------------------------------------ retries & fallback

    @Nested
    class Retries {

        @Test
        void temporaryFailuresBackOffExponentiallyThenSucceed() {
            email.failNext(2, SendFailure.temporary("timeout"));
            Notification n = service.send(req("u1", Category.TRANSACTIONAL).only(Channel.EMAIL).build());
            service.drain();
            Delivery d = n.deliveries().get(0);
            assertEquals(Instant.parse("2026-11-02T12:00:10Z"), d.nextAttemptAt());
            clock.advance(Duration.ofSeconds(9));
            assertEquals(0, service.dispatchDue(), "not due yet");
            clock.advance(Duration.ofSeconds(1));
            service.drain();
            assertEquals(Instant.parse("2026-11-02T12:00:30Z"), d.nextAttemptAt(), "second wait doubles to 20s");
            clock.advance(Duration.ofSeconds(20));
            service.drain();
            assertEquals(Delivery.Status.SENT, d.status());
            assertEquals(3, d.attempts());
        }

        @ParameterizedTest(name = "delay after attempt {0} = {1}s")
        @CsvSource({"1,10", "2,20", "3,25", "10,25"})
        void backoffIsCapped(int attempt, long seconds) {
            assertEquals(Duration.ofSeconds(seconds),
                    new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(25)).delayAfter(attempt));
        }

        @Test
        void givesUpAfterMaxAttempts() {
            email.failNext(5, SendFailure.temporary("down"));
            Notification n = service.send(req("u1", Category.TRANSACTIONAL).only(Channel.EMAIL).build());
            for (int i = 0; i < 5; i++) {
                service.drain();
                clock.advance(Duration.ofMinutes(1));
            }
            Delivery d = n.deliveries().get(0);
            assertEquals(Delivery.Status.FAILED, d.status());
            assertEquals(3, d.attempts());
            assertEquals(List.of(), service.pendingDeliveries());
        }

        @Test
        void permanentFailureStopsAtOnceAndFallsBack() {
            service.fallback(Channel.PUSH, Channel.SMS);
            push.failNext(1, SendFailure.permanent("bad token"));
            Notification n = service.send(req("u1", Category.SECURITY).only(Channel.PUSH).build());
            service.drain();
            assertEquals(Map.of(Channel.PUSH, Delivery.Status.FAILED, Channel.SMS, Delivery.Status.SENT), statuses(n));
            assertEquals(1, n.deliveries().get(0).attempts());
        }

        @Test
        void noFallbackWhenAnotherChannelAlreadyReachedTheUser() {
            service.fallback(Channel.PUSH, Channel.SMS);
            push.failNext(1, SendFailure.permanent("bad token"));
            Notification n = service.send(req("u1", Category.SECURITY).only(Channel.EMAIL, Channel.PUSH).build());
            service.drain();
            assertFalse(statuses(n).containsKey(Channel.SMS));
        }

        @Test
        void missingSenderIsAPermanentFailure() {
            NotificationService bare = new NotificationService(RetryPolicy.standard(), clock);
            bare.registerTemplate(Template.named("t").channel(Channel.EMAIL, "s", "b"));
            bare.registerUser(new Contact("u", "U", "u@x", null, null));
            Notification n = bare.send(NotificationRequest.to("u").template("t").category(Category.SECURITY).only(Channel.EMAIL).build());
            bare.drain();
            assertEquals(Delivery.Status.FAILED, n.deliveries().get(0).status());
        }
    }

    // ------------------------------------------------------------------ quiet hours, priority, rate limits

    @Nested
    class Policies {

        @Test
        void quietHoursHoldNormalMessagesButNotUrgentOnes() {
            clock.advance(Duration.ofHours(11));                                   // 23:00
            service.preferences("u1").setQuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0));
            Notification normal = service.send(req("u1", Category.TRANSACTIONAL).only(Channel.EMAIL).build());
            Notification urgent = service.send(req("u1", Category.TRANSACTIONAL).priority(Priority.HIGH).only(Channel.EMAIL).build());
            Notification security = service.send(req("u1", Category.SECURITY).only(Channel.SMS).build());
            service.drain();
            assertEquals(Delivery.Status.PENDING, normal.deliveries().get(0).status());
            assertEquals(Instant.parse("2026-11-03T07:00:00Z"), normal.deliveries().get(0).nextAttemptAt());
            assertEquals(Delivery.Status.SENT, urgent.deliveries().get(0).status());
            assertEquals(Delivery.Status.SENT, security.deliveries().get(0).status());
            clock.advance(Duration.ofHours(8));
            service.drain();
            assertEquals(Delivery.Status.SENT, normal.deliveries().get(0).status());
        }

        @ParameterizedTest(name = "{0} with quiet {1}-{2} -> {3}")
        @CsvSource({
                "2026-11-02T23:00:00Z,22:00,07:00,2026-11-03T07:00:00Z",
                "2026-11-02T03:00:00Z,22:00,07:00,2026-11-02T07:00:00Z",
                "2026-11-02T07:00:00Z,22:00,07:00,2026-11-02T07:00:00Z",
                "2026-11-02T21:59:00Z,22:00,07:00,2026-11-02T21:59:00Z",
                "2026-11-02T13:30:00Z,13:00,14:00,2026-11-02T14:00:00Z",
                "2026-11-02T14:00:00Z,13:00,14:00,2026-11-02T14:00:00Z"})
        void quietHoursWindow(String now, String from, String until, String expected) {
            Preferences p = Preferences.defaults();
            p.setQuietHours(LocalTime.parse(from), LocalTime.parse(until));
            assertEquals(Instant.parse(expected), p.quietUntil(Instant.parse(now)));
        }

        @Test
        void higherPriorityGoesFirst() {
            List<String> order = new ArrayList<>();
            service.addListener(new com.lld.messaging.notification.core.DeliveryListener() {
                @Override
                public void onSent(Delivery d) {
                    order.add(d.notificationId());
                }
            });
            Notification low = service.send(req("u1", Category.MARKETING).only(Channel.EMAIL).build());
            Notification normal = service.send(req("u1", Category.TRANSACTIONAL).only(Channel.EMAIL).build());
            Notification high = service.send(req("u1", Category.SECURITY).only(Channel.EMAIL).build());
            service.drain();
            assertEquals(List.of(high.id(), normal.id(), low.id()), order);
        }

        @Test
        void rateLimitDropsMarketingDelaysOthersAndNeverTouchesSecurity() {
            service.rateLimit(Channel.SMS, new RateLimiter(2, Duration.ofHours(1)));
            service.preferences("u1").setChannels(Category.MARKETING, EnumSet.of(Channel.SMS));
            service.preferences("u1").setChannels(Category.TRANSACTIONAL, EnumSet.of(Channel.SMS));
            service.send(req("u1", Category.MARKETING).build());
            service.send(req("u1", Category.MARKETING).build());
            service.drain();
            clock.advance(Duration.ofMinutes(10));
            Notification promo = service.send(req("u1", Category.MARKETING).build());
            Notification receipt = service.send(req("u1", Category.TRANSACTIONAL).build());
            Notification alert = service.send(req("u1", Category.SECURITY).only(Channel.SMS).build());
            service.drain();
            assertEquals(Delivery.Status.SKIPPED, promo.deliveries().get(0).status());
            assertEquals(Delivery.Status.SENT, alert.deliveries().get(0).status());
            Delivery r = receipt.deliveries().get(0);
            assertEquals(Delivery.Status.PENDING, r.status());
            assertEquals(Instant.parse("2026-11-02T13:00:00Z"), r.nextAttemptAt(), "first send + 1 hour");
            clock.advance(Duration.ofMinutes(50));
            service.drain();
            assertEquals(Delivery.Status.SENT, r.status());
            assertEquals(1, r.attempts(), "postponing is not an attempt");
        }

        @Test
        void slidingWindowForgetsOldSends() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(10));
            Instant t0 = Instant.parse("2026-11-02T12:00:00Z");
            assertEquals(t0, limiter.allowedAt("k", t0));
            limiter.record("k", t0);
            assertEquals(t0.plusSeconds(600), limiter.allowedAt("k", t0.plusSeconds(60)));
            assertEquals(t0.plusSeconds(600), limiter.allowedAt("k", t0.plusSeconds(600)));
            assertEquals(t0, limiter.allowedAt("other", t0));
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void parallelWorkersSendEachDeliveryExactlyOnce() throws Exception {
        for (int i = 0; i < 300; i++) {
            service.send(req("u1", Category.TRANSACTIONAL).only(Channel.IN_APP).build());
        }
        ExecutorService workers = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 6; w++) {
            futures.add(workers.submit(() -> {
                start.await();
                service.drain();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        workers.shutdown();
        assertEquals(300, inApp.outbox().size());
        assertEquals(List.of(), service.pendingDeliveries());
    }

    @Test
    void concurrentRetriesOfTheSameRequestCreateOneNotification() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(8);
        Set<Notification> seen = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(callers.submit(() -> seen.add(service.send(req("u1", Category.TRANSACTIONAL).idempotencyKey("same").build()))));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        callers.shutdown();
        assertEquals(1, seen.size());
    }
}
