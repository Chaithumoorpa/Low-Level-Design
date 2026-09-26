package com.lld.messaging.notification;

import com.lld.messaging.notification.channel.FakeSender;
import com.lld.messaging.notification.channel.SendFailure;
import com.lld.messaging.notification.core.DeliveryListener;
import com.lld.messaging.notification.core.ManualClock;
import com.lld.messaging.notification.core.NotificationService;
import com.lld.messaging.notification.model.Category;
import com.lld.messaging.notification.model.Channel;
import com.lld.messaging.notification.model.Contact;
import com.lld.messaging.notification.model.Delivery;
import com.lld.messaging.notification.model.Notification;
import com.lld.messaging.notification.model.NotificationException;
import com.lld.messaging.notification.model.NotificationRequest;
import com.lld.messaging.notification.policy.RateLimiter;
import com.lld.messaging.notification.policy.RetryPolicy;
import com.lld.messaging.notification.template.Template;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

/** A shop's notification service over one evening and the next morning, on a simulated clock. */
public class NotificationApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2026-11-02T21:30:00Z"));
        NotificationService service = new NotificationService(RetryPolicy.standard(), clock);
        FakeSender email = new FakeSender(Channel.EMAIL);
        FakeSender sms = new FakeSender(Channel.SMS);
        FakeSender push = new FakeSender(Channel.PUSH);
        FakeSender inApp = new FakeSender(Channel.IN_APP);
        List.of(email, sms, push, inApp).forEach(service::registerSender);
        service.fallback(Channel.PUSH, Channel.SMS);
        service.rateLimit(Channel.SMS, new RateLimiter(2, Duration.ofHours(1)));
        service.addListener(new DeliveryListener() {
            @Override
            public void onSent(Delivery d) {
                System.out.println("   [sent]    " + d + " after " + d.attempts() + " attempt(s)");
            }

            @Override
            public void onFailed(Delivery d) {
                System.out.println("   [failed]  " + d + ": " + last(d));
            }

            @Override
            public void onSkipped(Delivery d) {
                System.out.println("   [skipped] " + d + ": " + last(d));
            }
        });

        service.registerTemplate(Template.named("order-shipped")
                .channel(Channel.EMAIL, "Your order {order} is on its way", "Hi {name}, order {order} ships with {carrier}.")
                .channel(Channel.PUSH, "Shipped!", "Order {order} is on its way")
                .channel(Channel.SMS, null, "Order {order} shipped via {carrier}.")
                .channel(Channel.IN_APP, "Shipped", "Order {order} shipped"));
        service.registerTemplate(Template.named("login-alert")
                .channel(Channel.EMAIL, "New sign-in", "Hi {name}, new sign-in from {city}. Not you? Reset your password.")
                .channel(Channel.SMS, null, "New sign-in from {city}. Not you? Reset your password.")
                .channel(Channel.PUSH, "New sign-in", "From {city}"));
        service.registerTemplate(Template.named("sale")
                .channel(Channel.EMAIL, "{percent}% off tonight", "Hi {name}, everything is {percent}% off.")
                .channel(Channel.SMS, null, "{percent}% off everything tonight!"));

        service.registerUser(new Contact("asha", "Asha", "asha@example.com", "+15550001", "device-a"));
        service.registerUser(new Contact("ben", "Ben", "ben@example.com", null, "device-b"));
        service.preferences("asha").setQuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0));
        for (String user : List.of("asha", "ben")) {
            service.preferences(user).setChannels(Category.TRANSACTIONAL,
                    EnumSet.of(Channel.EMAIL, Channel.PUSH, Channel.IN_APP, Channel.SMS));
        }
        service.preferences("asha").setChannels(Category.MARKETING, EnumSet.of(Channel.EMAIL, Channel.SMS));
        service.preferences("ben").setChannels(Category.MARKETING, EnumSet.noneOf(Channel.class));

        step("21:30 order shipped for Asha (email provider times out twice)");
        email.failNext(2, SendFailure.temporary("SMTP timeout"));
        Notification shipped = service.send(NotificationRequest.to("asha").template("order-shipped")
                .with("name", "Asha").with("order", "#1042").with("carrier", "DHL")
                .category(Category.TRANSACTIONAL).idempotencyKey("order-1042-shipped").build());
        service.drain();
        System.out.println("   order service retries its call: same notification? "
                + (service.send(NotificationRequest.to("asha").template("order-shipped")
                .with("name", "Asha").with("order", "#1042").with("carrier", "DHL")
                .category(Category.TRANSACTIONAL).idempotencyKey("order-1042-shipped").build()) == shipped));
        advance(clock, service, Duration.ofSeconds(30));
        advance(clock, service, Duration.ofMinutes(1));

        advanceTo(clock, "2026-11-02T21:32:00Z");
        step("21:32 Ben's push token is dead: fall back to SMS (but Ben has no phone)");
        push.failNext(1, SendFailure.permanent("device not registered"));
        service.send(NotificationRequest.to("ben").template("order-shipped")
                .with("name", "Ben").with("order", "#2077").with("carrier", "UPS")
                .category(Category.TRANSACTIONAL).only(Channel.PUSH).build());
        service.drain();

        advanceTo(clock, "2026-11-02T21:33:00Z");
        step("21:33 three SMS promos to Asha (limit 2 SMS/hour, the shipment SMS used one); Ben opted out");
        for (String percent : List.of("20", "30", "40")) {
            service.send(NotificationRequest.to("asha").template("sale").with("name", "Asha").with("percent", percent)
                    .category(Category.MARKETING).only(Channel.SMS).build());
        }
        service.send(NotificationRequest.to("ben").template("sale").with("name", "Ben").with("percent", "20")
                .category(Category.MARKETING).only(Channel.EMAIL).build());
        service.drain();

        step("22:15 Asha: two login alerts (security skips quiet hours and limits) and a shipment SMS");
        advanceTo(clock, "2026-11-02T22:15:00Z");
        for (String city : List.of("Lisbon", "Oslo")) {
            service.send(NotificationRequest.to("asha").template("login-alert").with("name", "Asha").with("city", city)
                    .category(Category.SECURITY).only(Channel.SMS).build());
        }
        service.send(NotificationRequest.to("asha").template("order-shipped")
                .with("name", "Asha").with("order", "#1043").with("carrier", "DHL")
                .category(Category.TRANSACTIONAL).only(Channel.SMS).build());
        service.drain();
        System.out.println("   still pending: " + service.pendingDeliveries() + " until "
                + service.pendingDeliveries().get(0).nextAttemptAt());

        step("07:00 next morning");
        advanceTo(clock, "2026-11-03T07:00:00Z");
        service.drain();

        step("Missing variable is rejected up front");
        try {
            service.send(NotificationRequest.to("asha").template("sale").with("name", "Asha")
                    .category(Category.MARKETING).build());
        } catch (NotificationException e) {
            System.out.println("   [refused] " + e.getMessage());
        }

        step("Delivery log of " + shipped.id() + " email");
        shipped.deliveries().get(0).log().forEach(l -> System.out.println("   " + l));

        step("Outboxes");
        System.out.println("   EMAIL  " + email.outbox());
        System.out.println("   SMS    " + sms.outbox());
        System.out.println("   PUSH   " + push.outbox());
        System.out.println("   IN_APP " + inApp.outbox());
    }

    private static void advance(ManualClock clock, NotificationService service, Duration d) {
        clock.advance(d);
        System.out.println("   ... " + d.toSeconds() + "s later");
        service.drain();
    }

    private static void advanceTo(ManualClock clock, String instant) {
        clock.advance(Duration.between(clock.instant(), Instant.parse(instant)));
    }

    private static String last(Delivery d) {
        List<String> log = d.log();
        String line = log.get(log.size() - 1);
        return line.substring(line.indexOf(' ') + 1);
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }
}
