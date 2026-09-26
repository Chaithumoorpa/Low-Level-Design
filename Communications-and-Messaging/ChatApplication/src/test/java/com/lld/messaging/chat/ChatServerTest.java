package com.lld.messaging.chat;

import com.lld.messaging.chat.model.ChatEvent;
import com.lld.messaging.chat.model.ChatException;
import com.lld.messaging.chat.model.Conversation;
import com.lld.messaging.chat.model.DeliveryStatus;
import com.lld.messaging.chat.model.Membership;
import com.lld.messaging.chat.model.MessageView;
import com.lld.messaging.chat.server.ChatServer;
import com.lld.messaging.chat.server.ManualClock;
import com.lld.messaging.chat.server.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

class ChatServerTest {

    private ManualClock clock;
    private ChatServer server;
    private final List<String> pushes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2027-01-15T18:00:00Z"));
        server = new ChatServer(4, Duration.ofMinutes(15), clock);
        server.addOfflineNotifier((user, m) -> pushes.add(user + " " + m.seq()));
        for (String u : List.of("ana", "raj", "kai", "lee", "mo")) {
            server.register(u, u.substring(0, 1).toUpperCase() + u.substring(1));
        }
    }

    private static List<MessageView> received(Session s) {
        return s.drain().stream().filter(e -> e instanceof ChatEvent.NewMessage)
                .map(e -> ((ChatEvent.NewMessage) e).message()).toList();
    }

    private static List<Long> seqs(List<MessageView> views) {
        return views.stream().map(MessageView::seq).toList();
    }

    // ------------------------------------------------------------------ direct chats

    @Nested
    class Direct {

        @Test
        void oneConversationPerPairWhateverTheOrder() {
            Conversation a = server.direct("ana", "raj");
            assertSame(a, server.direct("raj", "ana"));
            assertEquals(Conversation.Type.DIRECT, a.type());
            assertThrows(ChatException.class, () -> server.direct("ana", "ana"));
            assertThrows(ChatException.class, () -> server.direct("ana", "ghost"));
        }

        @Test
        void messagesReachOtherDevicesButNotTheSendingOne() {
            Session anaPhone = server.connect("ana", "phone");
            Session anaLaptop = server.connect("ana", "laptop");
            Session raj = server.connect("raj", "phone");
            Conversation dm = server.direct("ana", "raj");
            server.send(anaPhone, dm.id(), "1", "hi");
            assertEquals(List.of(), received(anaPhone));
            assertEquals(1, received(anaLaptop).size());
            assertEquals("hi", received(raj).get(0).text());
        }

        @Test
        void sameClientIdIsStoredOnce() {
            Session ana = server.connect("ana", "phone");
            Conversation dm = server.direct("ana", "raj");
            MessageView first = server.send(ana, dm.id(), "x", "hello");
            assertEquals(first, server.send(ana, dm.id(), "x", "hello"));
            assertEquals(1, dm.lastSeq());
            assertEquals(2, server.send(ana, dm.id(), "y", "hello").seq(), "a new client id is a new message");
        }

        @Test
        void blockingStopsMessagesOneWay() {
            Session ana = server.connect("ana", "phone");
            Session raj = server.connect("raj", "phone");
            Conversation dm = server.direct("ana", "raj");
            server.block("raj", "ana");
            assertThrows(ChatException.class, () -> server.send(ana, dm.id(), "1", "hi"));
            server.send(raj, dm.id(), "2", "I can still write");
            server.unblock("raj", "ana");
            server.send(ana, dm.id(), "3", "hi again");
            assertEquals(2, dm.lastSeq());
        }

        @Test
        void messageValidation() {
            Session ana = server.connect("ana", "phone");
            Conversation dm = server.direct("ana", "raj");
            assertThrows(ChatException.class, () -> server.send(ana, dm.id(), "1", "  "));
            assertThrows(ChatException.class, () -> server.send(ana, dm.id(), "1", "x".repeat(4001)));
            assertThrows(ChatException.class, () -> server.send(ana, dm.id(), "1", "re", 9L), "reply to nothing");
            Session kai = server.connect("kai", "phone");
            assertThrows(ChatException.class, () -> server.send(kai, dm.id(), "1", "let me in"));
            server.disconnect(ana);
            assertThrows(ChatException.class, () -> server.send(ana, dm.id(), "2", "closed session"));
        }
    }

    // ------------------------------------------------------------------ receipts

    @Nested
    class Receipts {

        @Test
        void sentDeliveredReadTicks() {
            Session ana = server.connect("ana", "phone");
            Conversation dm = server.direct("ana", "raj");
            MessageView m = server.send(ana, dm.id(), "1", "hi");
            assertEquals(Map.of("raj", DeliveryStatus.SENT), server.receipts(dm.id(), m.seq()), "Raj is offline");
            assertEquals(List.of("raj 1"), pushes);
            Session raj = server.connect("raj", "phone");
            server.sync(raj);
            assertEquals(Map.of("raj", DeliveryStatus.DELIVERED), server.receipts(dm.id(), m.seq()));
            server.markRead("raj", dm.id(), 99);                                   // clamped
            assertEquals(Map.of("raj", DeliveryStatus.READ), server.receipts(dm.id(), m.seq()));
            assertTrue(ana.drain().stream().anyMatch(e -> e instanceof ChatEvent.Receipt r && r.readUpTo() == 1));
        }

        @Test
        void groupStatusIsTheSlowestMember() {
            Session ana = server.connect("ana", "phone");
            server.connect("raj", "phone");
            Conversation g = server.createGroup("ana", "g", List.of("raj", "kai"));
            MessageView m = server.send(ana, g.id(), "1", "hello all");
            assertEquals(Map.of("raj", DeliveryStatus.DELIVERED, "kai", DeliveryStatus.SENT), server.receipts(g.id(), m.seq()));
            assertEquals(DeliveryStatus.SENT, server.overallStatus(g.id(), m.seq()));
            server.markRead("raj", g.id(), m.seq());
            server.markRead("kai", g.id(), m.seq());
            assertEquals(DeliveryStatus.READ, server.overallStatus(g.id(), m.seq()));
        }

        @Test
        void readPointerNeverMovesBackAndUnreadIgnoresOwnAndDeleted() {
            Session ana = server.connect("ana", "phone");
            Session raj = server.connect("raj", "phone");
            Conversation dm = server.direct("ana", "raj");
            for (int i = 1; i <= 5; i++) {
                server.send(ana, dm.id(), "a" + i, "m" + i);
            }
            server.send(raj, dm.id(), "r1", "mine");                               // marks Raj read up to 6
            assertEquals(0, server.unreadCount("raj", dm.id()));
            server.send(ana, dm.id(), "a6", "later");
            server.send(ana, dm.id(), "a7", "oops");
            server.delete(ana, dm.id(), 8);
            assertEquals(1, server.unreadCount("raj", dm.id()));
            server.markRead("raj", dm.id(), 2);
            assertEquals(1, server.unreadCount("raj", dm.id()), "going back does nothing");
        }

        @Test
        void mutedConversationsGetSilentEventsAndNoPush() {
            Session ana = server.connect("ana", "phone");
            Session raj = server.connect("raj", "phone");
            Conversation dm = server.direct("ana", "raj");
            server.setMuted("raj", dm.id(), true);
            server.send(ana, dm.id(), "1", "psst");
            ChatEvent.NewMessage e = (ChatEvent.NewMessage) raj.drain().get(0);
            assertTrue(e.silent());
            server.disconnect(raj);
            server.send(ana, dm.id(), "2", "still muted");
            assertEquals(List.of(), pushes);
        }
    }

    // ------------------------------------------------------------------ groups

    @Nested
    class Groups {

        @Test
        void onlyAdminsManageMembersAndSizeIsLimited() {
            Conversation g = server.createGroup("ana", "g", List.of("raj"));
            assertThrows(ChatException.class, () -> server.addMember("raj", g.id(), "kai"));
            server.addMember("ana", g.id(), "kai");
            assertThrows(ChatException.class, () -> server.addMember("ana", g.id(), "kai"), "already in");
            server.addMember("ana", g.id(), "lee");
            assertThrows(ChatException.class, () -> server.addMember("ana", g.id(), "mo"), "max 4");
            assertThrows(ChatException.class, () -> server.createGroup("ana", "big", List.of("raj", "kai", "lee", "mo")));
            server.removeMember("ana", g.id(), "lee");
            assertThrows(ChatException.class, () -> server.removeMember("ana", g.id(), "ana"));
            assertThrows(ChatException.class, () -> server.addMember("ana", server.direct("ana", "raj").id(), "kai"));
        }

        @Test
        void newMembersDontSeeOlderHistory() {
            Session ana = server.connect("ana", "phone");
            Conversation g = server.createGroup("ana", "g", List.of("raj"));
            server.send(ana, g.id(), "1", "secret plans");
            server.addMember("ana", g.id(), "kai");
            server.send(ana, g.id(), "2", "welcome");
            List<MessageView> kaiHistory = server.history("kai", g.id(), Long.MAX_VALUE, 50);
            assertEquals(List.of("Ana added Kai", "welcome"), kaiHistory.stream().map(MessageView::text).toList());
            assertThrows(ChatException.class, () -> server.send(server.connect("kai", "p"), g.id(), "k", "re", 2L),
                    "can't reply to a message you can't see");
            assertEquals(List.of(), server.search("kai", g.id(), "secret"));
        }

        @Test
        void removedMembersStopReceiving() {
            Session ana = server.connect("ana", "phone");
            Session raj = server.connect("raj", "phone");
            Conversation g = server.createGroup("ana", "g", List.of("raj"));
            server.removeMember("ana", g.id(), "raj");
            raj.drain();
            server.send(ana, g.id(), "1", "after");
            assertEquals(List.of(), received(raj));
            assertThrows(ChatException.class, () -> server.history("raj", g.id(), Long.MAX_VALUE, 10));
        }

        @Test
        void lastAdminLeavingPromotesTheOldestMember() {
            Conversation g = server.createGroup("ana", "g", List.of("raj", "kai"));
            server.leave("ana", g.id());
            assertEquals(Membership.Role.ADMIN, g.member("raj").orElseThrow().role());
            assertEquals(Membership.Role.MEMBER, g.member("kai").orElseThrow().role());
            server.promote("raj", g.id(), "kai");
            server.leave("raj", g.id());
            assertEquals(List.of("kai"), g.members().stream().map(Membership::userId).toList());
            assertThrows(ChatException.class, () -> server.leave("ana", server.direct("ana", "raj").id()));
        }
    }

    // ------------------------------------------------------------------ edit, delete, history

    @Nested
    class Editing {

        @Test
        void editRulesAndWindow() {
            Session ana = server.connect("ana", "phone");
            Session raj = server.connect("raj", "phone");
            Conversation dm = server.direct("ana", "raj");
            MessageView m = server.send(ana, dm.id(), "1", "helo");
            assertEquals("hello", server.edit(ana, dm.id(), m.seq(), "hello").text());
            assertTrue(raj.drain().stream().anyMatch(e -> e instanceof ChatEvent.MessageUpdated u && u.message().edited()));
            assertThrows(ChatException.class, () -> server.edit(raj, dm.id(), m.seq(), "mine now"));
            clock.advance(Duration.ofMinutes(15));
            server.edit(ana, dm.id(), m.seq(), "still ok at exactly 15 min");
            clock.advance(Duration.ofSeconds(1));
            assertThrows(ChatException.class, () -> server.edit(ana, dm.id(), m.seq(), "too late"));
        }

        @Test
        void deleteLeavesATombstoneAndAdminsCanModerate() {
            Session ana = server.connect("ana", "phone");
            Session raj = server.connect("raj", "phone");
            Conversation g = server.createGroup("ana", "g", List.of("raj", "kai"));
            MessageView bad = server.send(raj, g.id(), "1", "spam");
            MessageView mine = server.send(ana, g.id(), "2", "hi");
            assertThrows(ChatException.class, () -> server.delete(raj, g.id(), mine.seq()));
            MessageView gone = server.delete(ana, g.id(), bad.seq());
            assertTrue(gone.deleted());
            assertEquals("", gone.text());
            assertEquals(bad.seq(), gone.seq(), "the seq stays so clients keep their place");
            assertThrows(ChatException.class, () -> server.edit(raj, g.id(), bad.seq(), "undo"));
            assertEquals(List.of(), server.search("ana", g.id(), "spam"));
        }

        @Test
        void historyPagesBackwards() {
            Session ana = server.connect("ana", "phone");
            Conversation dm = server.direct("ana", "raj");
            for (int i = 1; i <= 10; i++) {
                server.send(ana, dm.id(), "c" + i, "m" + i);
            }
            assertEquals(List.of(8L, 9L, 10L), seqs(server.history("ana", dm.id(), Long.MAX_VALUE, 3)));
            assertEquals(List.of(5L, 6L, 7L), seqs(server.history("ana", dm.id(), 8, 3)));
            assertEquals(List.of(1L, 2L), seqs(server.history("ana", dm.id(), 3, 3)));
            assertEquals(List.of(), server.history("ana", dm.id(), 1, 3));
        }
    }

    // ------------------------------------------------------------------ presence & sync

    @Test
    void presenceAndLastSeen() {
        Session a = server.connect("ana", "phone");
        Session b = server.connect("ana", "laptop");
        assertTrue(server.isOnline("ana"));
        server.disconnect(a);
        assertTrue(server.isOnline("ana"), "laptop still connected");
        clock.advance(Duration.ofMinutes(3));
        server.disconnect(b);
        assertFalse(server.isOnline("ana"));
        assertEquals(Instant.parse("2027-01-15T18:03:00Z"), server.lastSeen("ana").orElseThrow());
    }

    @Test
    void reconnectingDeviceSyncsOnlyWhatItMissedNewDeviceGetsAll() {
        Session ana = server.connect("ana", "phone");
        Session raj = server.connect("raj", "phone");
        Conversation dm = server.direct("ana", "raj");
        server.send(ana, dm.id(), "1", "one");
        server.send(ana, dm.id(), "2", "two");
        server.disconnect(raj);
        server.send(ana, dm.id(), "3", "three");
        server.send(ana, dm.id(), "4", "four");
        Session rajAgain = server.connect("raj", "phone");
        assertEquals(List.of(3L, 4L), seqs(server.sync(rajAgain).get(dm.id())));
        assertEquals(Map.of(), server.sync(rajAgain), "nothing new");
        Session rajTablet = server.connect("raj", "tablet");
        assertEquals(List.of(1L, 2L, 3L, 4L), seqs(server.sync(rajTablet).get(dm.id())));
    }

    @Test
    void typingIsEphemeral() {
        Session ana = server.connect("ana", "phone");
        Session raj = server.connect("raj", "phone");
        Conversation dm = server.direct("ana", "raj");
        server.typing(ana, dm.id());
        assertEquals(List.of(new ChatEvent.Typing(dm.id(), "ana")), raj.drain());
        assertEquals(0, dm.lastSeq());
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentSendersProduceOneGaplessOrderEveryoneSees() throws Exception {
        Conversation g = server.createGroup("ana", "g", List.of("raj", "kai", "lee"));
        Session watcher = server.connect("lee", "phone");
        List<Session> senders = List.of(server.connect("ana", "p"), server.connect("raj", "p"), server.connect("kai", "p"));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Session s : senders) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    server.send(s, g.id(), s.userId() + i, "msg " + i);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(601, g.lastSeq(), "600 + the 'created' system message");
        List<Long> seen = seqs(received(watcher));
        assertEquals(600, seen.size());
        for (int i = 1; i < seen.size(); i++) {
            assertEquals(seen.get(i - 1) + 1, seen.get(i), "in order, no gaps");
        }
    }
}
