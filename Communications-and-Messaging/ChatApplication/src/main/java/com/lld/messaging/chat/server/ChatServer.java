package com.lld.messaging.chat.server;

import com.lld.messaging.chat.model.ChatEvent;
import com.lld.messaging.chat.model.ChatException;
import com.lld.messaging.chat.model.Conversation;
import com.lld.messaging.chat.model.DeliveryStatus;
import com.lld.messaging.chat.model.Membership;
import com.lld.messaging.chat.model.Message;
import com.lld.messaging.chat.model.MessageView;
import com.lld.messaging.chat.model.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Facade for the chat backend: users and devices, conversations, messages, receipts, presence.
 *
 * <p><b>Ordering:</b> each conversation has its own lock. Assigning the next seq, storing the message
 * and pushing it to connected devices all happen under that lock, so every device sees a conversation's
 * messages in the same order with no gaps. Different conversations proceed in parallel.
 *
 * <p><b>Delivery:</b> online devices get events pushed; a message pushed to any device of a member
 * counts as delivered to that member. Offline members get an {@link OfflineNotifier} call and catch up
 * with {@link #sync} on reconnect.
 */
public final class ChatServer {

    public static final int MAX_TEXT = 4000;

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, List<Session>> sessions = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> deviceCursors = new ConcurrentHashMap<>();
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, String> directByPair = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> blocked = new ConcurrentHashMap<>();
    private final List<OfflineNotifier> offlineNotifiers = new CopyOnWriteArrayList<>();
    private final AtomicLong conversationSeq = new AtomicLong();

    private final int maxGroupSize;
    private final Duration editWindow;
    private final Clock clock;

    public ChatServer(int maxGroupSize, Duration editWindow, Clock clock) {
        this.maxGroupSize = maxGroupSize;
        this.editWindow = editWindow;
        this.clock = Objects.requireNonNull(clock);
    }

    public void addOfflineNotifier(OfflineNotifier notifier) {
        offlineNotifiers.add(notifier);
    }

    // ------------------------------------------------------------------ users, devices, presence

    public User register(String id, String name) {
        User u = new User(id, name);
        if (users.putIfAbsent(id, u) != null) {
            throw new ChatException("User " + id + " exists");
        }
        return u;
    }

    public Session connect(String userId, String deviceId) {
        user(userId);
        Map<String, Long> cursor = deviceCursors.computeIfAbsent(userId + "|" + deviceId, k -> new ConcurrentHashMap<>());
        Session s = new Session(userId, deviceId, cursor);                  // same device = same cursor
        sessions.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(s);
        return s;
    }

    public void disconnect(Session session) {
        session.close();
        List<Session> mine = sessions.getOrDefault(session.userId(), List.of());
        mine.remove(session);
        if (mine.isEmpty()) {
            lastSeen.put(session.userId(), clock.instant());
        }
    }

    public boolean isOnline(String userId) {
        return !sessions.getOrDefault(userId, List.of()).isEmpty();
    }

    public Optional<Instant> lastSeen(String userId) {
        return isOnline(userId) ? Optional.empty() : Optional.ofNullable(lastSeen.get(userId));
    }

    public void block(String userId, String blockedId) {
        user(userId);
        user(blockedId);
        blocked.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(blockedId);
    }

    public void unblock(String userId, String blockedId) {
        blocked.getOrDefault(userId, Set.of()).remove(blockedId);
    }

    // ------------------------------------------------------------------ conversations

    /** The one direct chat between two users (created on first use, same id afterwards). */
    public Conversation direct(String a, String b) {
        user(a);
        user(b);
        if (a.equals(b)) {
            throw new ChatException("Can't chat with yourself");
        }
        String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        String id = directByPair.computeIfAbsent(pair, p -> {
            Conversation c = new Conversation("D" + conversationSeq.incrementAndGet(), Conversation.Type.DIRECT, pair);
            c.addMember(new Membership(a, Membership.Role.MEMBER, 0));
            c.addMember(new Membership(b, Membership.Role.MEMBER, 0));
            conversations.put(c.id(), c);
            return c.id();
        });
        return conversations.get(id);
    }

    public Conversation createGroup(String creatorId, String name, List<String> memberIds) {
        user(creatorId);
        memberIds.forEach(this::user);
        List<String> everyone = new ArrayList<>();
        everyone.add(creatorId);
        memberIds.stream().filter(m -> !everyone.contains(m)).forEach(everyone::add);
        if (everyone.size() > maxGroupSize) {
            throw new ChatException("Groups are limited to " + maxGroupSize + " members");
        }
        Conversation c = new Conversation("G" + conversationSeq.incrementAndGet(), Conversation.Type.GROUP, name);
        synchronized (c) {
            for (String id : everyone) {
                c.addMember(new Membership(id, id.equals(creatorId) ? Membership.Role.ADMIN : Membership.Role.MEMBER, 0));
            }
            conversations.put(c.id(), c);
            systemMessage(c, name(creatorId) + " created \"" + name + "\"");
        }
        return c;
    }

    /** Admins only. New members see messages from now on, not the history before they joined. */
    public void addMember(String actorId, String conversationId, String userId) {
        user(userId);
        Conversation c = conversation(conversationId);
        synchronized (c) {
            requireGroupAdmin(c, actorId);
            if (c.member(userId).isPresent()) {
                throw new ChatException(name(userId) + " is already in " + c.name());
            }
            if (c.members().size() >= maxGroupSize) {
                throw new ChatException(c.name() + " is full (" + maxGroupSize + ")");
            }
            c.addMember(new Membership(userId, Membership.Role.MEMBER, c.lastSeq()));
            systemMessage(c, name(actorId) + " added " + name(userId));
        }
    }

    public void removeMember(String actorId, String conversationId, String userId) {
        Conversation c = conversation(conversationId);
        synchronized (c) {
            requireGroupAdmin(c, actorId);
            if (actorId.equals(userId)) {
                throw new ChatException("Use leave to leave a group");
            }
            requireMember(c, userId);
            c.removeMember(userId);
            systemMessage(c, name(actorId) + " removed " + name(userId));
        }
    }

    /** Leaving as the last admin hands admin to the longest-standing member. */
    public void leave(String userId, String conversationId) {
        Conversation c = conversation(conversationId);
        synchronized (c) {
            if (c.type() == Conversation.Type.DIRECT) {
                throw new ChatException("You can't leave a direct chat (block the user instead)");
            }
            Membership m = requireMember(c, userId);
            c.removeMember(userId);
            systemMessage(c, name(userId) + " left");
            boolean adminLeft = c.members().stream().noneMatch(x -> x.role() == Membership.Role.ADMIN);
            if (m.role() == Membership.Role.ADMIN && adminLeft && !c.members().isEmpty()) {
                Membership next = c.members().iterator().next();
                next.setRole(Membership.Role.ADMIN);
                systemMessage(c, name(next.userId()) + " is now an admin");
            }
        }
    }

    public void promote(String actorId, String conversationId, String userId) {
        Conversation c = conversation(conversationId);
        synchronized (c) {
            requireGroupAdmin(c, actorId);
            requireMember(c, userId).setRole(Membership.Role.ADMIN);
            systemMessage(c, name(actorId) + " made " + name(userId) + " an admin");
        }
    }

    public void setMuted(String userId, String conversationId, boolean muted) {
        Conversation c = conversation(conversationId);
        synchronized (c) {
            requireMember(c, userId).setMuted(muted);
        }
    }

    // ------------------------------------------------------------------ messages

    /**
     * Sends a message. {@code clientMessageId} is generated by the app per message: if the app retries
     * after a network error, the same id returns the already stored message instead of a duplicate.
     */
    public MessageView send(Session from, String conversationId, String clientMessageId, String text, Long replyToSeq) {
        requireOpen(from);
        if (text == null || text.isBlank()) {
            throw new ChatException("Empty message");
        }
        if (text.length() > MAX_TEXT) {
            throw new ChatException("Message longer than " + MAX_TEXT + " characters");
        }
        Conversation c = conversation(conversationId);
        synchronized (c) {
            Membership sender = requireMember(c, from.userId());
            Optional<Message> duplicate = c.byClientId(from.userId(), clientMessageId);
            if (duplicate.isPresent()) {
                return duplicate.get().view();
            }
            if (c.type() == Conversation.Type.DIRECT) {
                for (Membership other : c.members()) {
                    if (!other.userId().equals(from.userId())
                            && blocked.getOrDefault(other.userId(), Set.of()).contains(from.userId())) {
                        throw new ChatException(name(other.userId()) + " is not accepting your messages");
                    }
                }
            }
            if (replyToSeq != null) {
                Message target = c.message(replyToSeq).orElseThrow(() -> new ChatException("No message #" + replyToSeq));
                if (!sender.canSee(target.seq())) {
                    throw new ChatException("No message #" + replyToSeq);
                }
            }
            Message m = c.append(from.userId(), text.strip(), clock.instant(), replyToSeq, false);
            c.rememberClientId(from.userId(), clientMessageId, m);
            sender.advance(m.seq(), m.seq());                  // you have read your own message
            from.seen(c.id(), m.seq());
            fanOut(c, m, from);
            return m.view();
        }
    }

    public MessageView send(Session from, String conversationId, String clientMessageId, String text) {
        return send(from, conversationId, clientMessageId, text, null);
    }

    /** Only the sender, only within the edit window, not after deletion. */
    public MessageView edit(Session by, String conversationId, long seq, String newText) {
        requireOpen(by);
        if (newText == null || newText.isBlank() || newText.length() > MAX_TEXT) {
            throw new ChatException("Invalid text");
        }
        Conversation c = conversation(conversationId);
        synchronized (c) {
            requireMember(c, by.userId());
            Message m = c.message(seq).orElseThrow(() -> new ChatException("No message #" + seq));
            if (m.system() || !m.senderId().equals(by.userId())) {
                throw new ChatException("Only the sender can edit #" + seq);
            }
            if (m.deleted()) {
                throw new ChatException("#" + seq + " was deleted");
            }
            if (clock.instant().isAfter(m.sentAt().plus(editWindow))) {
                throw new ChatException("#" + seq + " can only be edited within " + editWindow.toMinutes() + " minutes");
            }
            m.edit(newText.strip());
            broadcast(c, new ChatEvent.MessageUpdated(c.id(), m.view()), null);
            return m.view();
        }
    }

    /** Delete for everyone: the sender, or a group admin (moderation). Leaves a tombstone. */
    public MessageView delete(Session by, String conversationId, long seq) {
        requireOpen(by);
        Conversation c = conversation(conversationId);
        synchronized (c) {
            Membership actor = requireMember(c, by.userId());
            Message m = c.message(seq).orElseThrow(() -> new ChatException("No message #" + seq));
            boolean own = !m.system() && m.senderId().equals(by.userId());
            boolean moderator = c.type() == Conversation.Type.GROUP && actor.role() == Membership.Role.ADMIN;
            if (!own && !moderator) {
                throw new ChatException("Not allowed to delete #" + seq);
            }
            if (!m.deleted()) {
                m.delete();
                broadcast(c, new ChatEvent.MessageUpdated(c.id(), m.view()), null);
            }
            return m.view();
        }
    }

    public void typing(Session by, String conversationId) {
        requireOpen(by);
        Conversation c = conversation(conversationId);
        synchronized (c) {
            requireMember(c, by.userId());
            for (Membership m : c.members()) {
                if (!m.userId().equals(by.userId())) {
                    sessions.getOrDefault(m.userId(), List.of())
                            .forEach(s -> s.push(new ChatEvent.Typing(c.id(), by.userId())));
                }
            }
        }
    }

    // ------------------------------------------------------------------ receipts

    /** The user has seen everything up to {@code seq} (clamped to the latest message). */
    public void markRead(String userId, String conversationId, long seq) {
        Conversation c = conversation(conversationId);
        synchronized (c) {
            Membership m = requireMember(c, userId);
            if (m.advance(0, Math.min(seq, c.lastSeq()))) {
                broadcast(c, new ChatEvent.Receipt(c.id(), userId, m.deliveredUpTo(), m.readUpTo()), null);
            }
        }
    }

    /** Ticks for one message as its sender sees them: per recipient, and overall (the lowest). */
    public Map<String, DeliveryStatus> receipts(String conversationId, long seq) {
        Conversation c = conversation(conversationId);
        synchronized (c) {
            Message msg = c.message(seq).orElseThrow(() -> new ChatException("No message #" + seq));
            Map<String, DeliveryStatus> out = new LinkedHashMap<>();
            for (Membership m : c.members()) {
                if (m.userId().equals(msg.senderId()) || !m.canSee(seq)) {
                    continue;
                }
                out.put(m.userId(), m.readUpTo() >= seq ? DeliveryStatus.READ
                        : m.deliveredUpTo() >= seq ? DeliveryStatus.DELIVERED : DeliveryStatus.SENT);
            }
            return out;
        }
    }

    public DeliveryStatus overallStatus(String conversationId, long seq) {
        return receipts(conversationId, seq).values().stream().min(Comparator.naturalOrder()).orElse(DeliveryStatus.READ);
    }

    public long unreadCount(String userId, String conversationId) {
        Conversation c = conversation(conversationId);
        synchronized (c) {
            Membership m = requireMember(c, userId);
            return c.range(m.readUpTo(), c.lastSeq()).stream()
                    .filter(x -> !userId.equals(x.senderId()) && !x.deleted()).count();
        }
    }

    // ------------------------------------------------------------------ history & sync

    /** A page of history before {@code beforeSeq} (exclusive), oldest first. Hidden: pre-join messages. */
    public List<MessageView> history(String userId, String conversationId, long beforeSeq, int limit) {
        Conversation c = conversation(conversationId);
        synchronized (c) {
            Membership m = requireMember(c, userId);
            long to = Math.min(beforeSeq - 1, c.lastSeq());
            long from = Math.max(m.joinedAfterSeq(), to - limit);
            return c.range(from, to).stream().map(Message::view).toList();
        }
    }

    public List<MessageView> search(String userId, String conversationId, String query) {
        Conversation c = conversation(conversationId);
        String q = query.toLowerCase(Locale.ROOT);
        synchronized (c) {
            Membership m = requireMember(c, userId);
            return c.range(m.joinedAfterSeq(), c.lastSeq()).stream()
                    .filter(x -> !x.deleted() && !x.system() && x.text().toLowerCase(Locale.ROOT).contains(q))
                    .map(Message::view).toList();
        }
    }

    /**
     * Catch-up after (re)connecting: every visible message newer than what this device has seen, in
     * every conversation of the user. Marks them delivered.
     */
    public Map<String, List<MessageView>> sync(Session session) {
        requireOpen(session);
        Map<String, List<MessageView>> out = new LinkedHashMap<>();
        List<Conversation> mine = conversations.values().stream()
                .sorted(Comparator.comparing(Conversation::id)).toList();
        for (Conversation c : mine) {
            synchronized (c) {
                Optional<Membership> m = c.member(session.userId());
                if (m.isEmpty()) {
                    continue;
                }
                long after = Math.max(session.lastSeq(c.id()), m.get().joinedAfterSeq());
                List<MessageView> missed = c.range(after, c.lastSeq()).stream().map(Message::view).toList();
                if (!missed.isEmpty()) {
                    out.put(c.id(), missed);
                    session.seen(c.id(), c.lastSeq());
                    if (m.get().advance(c.lastSeq(), 0)) {
                        broadcast(c, new ChatEvent.Receipt(c.id(), session.userId(), m.get().deliveredUpTo(),
                                m.get().readUpTo()), session);
                    }
                }
            }
        }
        return out;
    }

    public List<Conversation> conversationsOf(String userId) {
        return conversations.values().stream().filter(c -> {
            synchronized (c) {
                return c.member(userId).isPresent();
            }
        }).sorted(Comparator.comparing(Conversation::id)).toList();
    }

    public Conversation conversation(String id) {
        Conversation c = conversations.get(id);
        if (c == null) {
            throw new ChatException("No conversation " + id);
        }
        return c;
    }

    // ------------------------------------------------------------------ internals (conversation lock held)

    private void fanOut(Conversation c, Message m, Session origin) {
        MessageView view = m.view();
        List<ChatEvent.Receipt> receipts = new ArrayList<>();
        for (Membership member : c.members()) {
            List<Session> devices = sessions.getOrDefault(member.userId(), List.of());
            boolean isSender = member.userId().equals(m.senderId());
            for (Session s : devices) {
                if (s != origin) {
                    s.push(new ChatEvent.NewMessage(c.id(), view, member.muted() || isSender));
                }
            }
            if (isSender) {
                continue;
            }
            if (!devices.isEmpty()) {
                if (member.advance(m.seq(), 0)) {
                    receipts.add(new ChatEvent.Receipt(c.id(), member.userId(), member.deliveredUpTo(), member.readUpTo()));
                }
            } else if (!member.muted() && !m.system()) {
                offlineNotifiers.forEach(n -> n.notifyOffline(member.userId(), view));
            }
        }
        receipts.forEach(r -> broadcast(c, r, null));
    }

    private void systemMessage(Conversation c, String text) {
        Message m = c.append(null, text, clock.instant(), null, true);
        fanOut(c, m, null);
    }

    private void broadcast(Conversation c, ChatEvent event, Session except) {
        for (Membership member : c.members()) {
            for (Session s : sessions.getOrDefault(member.userId(), List.of())) {
                if (s != except) {
                    s.push(event);
                }
            }
        }
    }

    private Membership requireMember(Conversation c, String userId) {
        return c.member(userId).orElseThrow(() -> new ChatException(name(userId) + " is not in this conversation"));
    }

    private void requireGroupAdmin(Conversation c, String userId) {
        if (c.type() != Conversation.Type.GROUP) {
            throw new ChatException("Only groups have admins");
        }
        if (requireMember(c, userId).role() != Membership.Role.ADMIN) {
            throw new ChatException(name(userId) + " is not an admin of " + c.name());
        }
    }

    private static void requireOpen(Session s) {
        if (!s.isOpen()) {
            throw new ChatException("Session " + s + " is closed");
        }
    }

    private User user(String id) {
        User u = users.get(id);
        if (u == null) {
            throw new ChatException("No user " + id);
        }
        return u;
    }

    private String name(String userId) {
        User u = users.get(userId);
        return u == null ? userId : u.name();
    }
}
