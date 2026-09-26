package com.lld.messaging.chat;

import com.lld.messaging.chat.model.ChatEvent;
import com.lld.messaging.chat.model.ChatException;
import com.lld.messaging.chat.model.Conversation;
import com.lld.messaging.chat.model.MessageView;
import com.lld.messaging.chat.server.ChatServer;
import com.lld.messaging.chat.server.ManualClock;
import com.lld.messaging.chat.server.Session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** Three friends, a direct chat and a trip-planning group, on a simulated clock. */
public class ChatApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2027-01-15T18:00:00Z"));
        ChatServer server = new ChatServer(50, Duration.ofMinutes(15), clock);
        server.addOfflineNotifier((user, m) -> System.out.println("   [push notification to " + user + "] " + m));
        server.register("ana", "Ana");
        server.register("raj", "Raj");
        server.register("kai", "Kai");

        Session anaPhone = server.connect("ana", "phone");
        Session anaLaptop = server.connect("ana", "laptop");
        Session rajPhone = server.connect("raj", "phone");

        step("Direct chat: Ana texts Raj from her phone");
        Conversation dm = server.direct("ana", "raj");
        MessageView hi = server.send(anaPhone, dm.id(), "c-1", "Hi Raj! Free on Saturday?");
        System.out.println("   stored " + hi + "; ticks: " + server.receipts(dm.id(), hi.seq()));
        show("Raj's phone", rajPhone);
        show("Ana's laptop (same account, other device)", anaLaptop);
        server.markRead("raj", dm.id(), hi.seq());
        System.out.println("   Raj opens the chat; ticks: " + server.receipts(dm.id(), hi.seq()));
        attempt(() -> server.send(anaPhone, dm.id(), "c-1", "Hi Raj! Free on Saturday?"));
        System.out.println("   (network retry with the same client id returned the stored #" + hi.seq() + ", no duplicate)");

        step("Group: Ana creates 'Lisbon trip' with Raj; Kai is offline");
        Conversation trip = server.createGroup("ana", "Lisbon trip", List.of("raj"));
        server.send(rajPhone, trip.id(), "r-1", "Flights are cheap in May");
        server.send(anaLaptop, trip.id(), "a-1", "Let's do it. Adding Kai");
        server.addMember("ana", trip.id(), "kai");
        server.send(rajPhone, trip.id(), "r-2", "Welcome Kai! Hotel or apartment?");
        attempt(() -> {
            server.addMember("raj", trip.id(), "kai");
            return "ok";
        });

        step("Kai connects and syncs (sees only what came after he joined)");
        clock.advance(Duration.ofMinutes(5));
        Session kaiPhone = server.connect("kai", "phone");
        server.sync(kaiPhone).forEach((conv, msgs) -> msgs.forEach(m -> System.out.println("   " + conv + " " + m)));
        MessageView reply = server.send(kaiPhone, trip.id(), "k-1", "Apartment, definitely", 5L);
        System.out.println("   Kai: " + reply);
        System.out.println("   Raj's question #5 ticks: " + server.receipts(trip.id(), 5));

        step("Edits, deletes and their limits");
        MessageView typo = server.send(anaLaptop, trip.id(), "a-2", "I'll book the flihgts");
        server.edit(anaLaptop, trip.id(), typo.seq(), "I'll book the flights");
        attempt(() -> server.edit(rajPhone, trip.id(), typo.seq(), "hacked"));
        clock.advance(Duration.ofMinutes(20));
        attempt(() -> server.edit(anaLaptop, trip.id(), typo.seq(), "too late?"));
        attempt(() -> server.delete(rajPhone, trip.id(), 2));
        attempt(() -> server.delete(anaPhone, trip.id(), reply.seq()));
        System.out.println("   (Ana is an admin, so she may remove any message in her group)");

        step("Raj goes offline, then comes back on the same phone");
        server.disconnect(rajPhone);
        System.out.println("   Raj online? " + server.isOnline("raj") + ", last seen " + server.lastSeen("raj").orElseThrow());
        server.send(kaiPhone, trip.id(), "k-2", "Booked the apartment!");
        server.setMuted("raj", dm.id(), true);
        server.send(anaPhone, dm.id(), "c-2", "(muted chat: no push for this one)");
        clock.advance(Duration.ofMinutes(30));
        Session rajAgain = server.connect("raj", "phone");
        server.sync(rajAgain).forEach((conv, msgs) -> msgs.forEach(m -> System.out.println("   catch-up " + conv + " " + m)));
        System.out.println("   unread for Raj: dm=" + server.unreadCount("raj", dm.id()) + ", trip=" + server.unreadCount("raj", trip.id()));

        step("Last admin leaves the group");
        server.leave("ana", trip.id());
        server.history("raj", trip.id(), Long.MAX_VALUE, 3).forEach(m -> System.out.println("   " + m));

        step("Blocking");
        server.block("kai", "ana");
        Conversation anaKai = server.direct("ana", "kai");
        attempt(() -> server.send(anaPhone, anaKai.id(), "c-9", "Hey Kai"));

        step("Search Raj's view of the trip");
        System.out.println("   'apartment' -> " + server.search("raj", trip.id(), "apartment"));
    }

    /** Prints the messages a device received (skipping receipt/typing chatter). */
    private static void show(String who, Session s) {
        for (ChatEvent e : s.drain()) {
            if (e instanceof ChatEvent.NewMessage m) {
                System.out.println("   " + who + " <- " + m.message() + (m.silent() ? " (silent)" : ""));
            }
        }
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (ChatException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
