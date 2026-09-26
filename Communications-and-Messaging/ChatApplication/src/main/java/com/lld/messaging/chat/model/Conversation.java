package com.lld.messaging.chat.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A direct chat (exactly two people, forever) or a group (named, admins, members come and go).
 * Messages get consecutive sequence numbers under the conversation's lock, which is what gives
 * everyone the same order. Every method here is called by the server while holding that lock
 * ({@code synchronized (conversation)}).
 */
public final class Conversation {

    public enum Type {
        DIRECT, GROUP
    }

    private final String id;
    private final Type type;
    private final String name;
    private final Map<String, Membership> members = new LinkedHashMap<>();     // join order
    private final List<Message> messages = new ArrayList<>();                 // index = seq - 1
    private final Map<String, Message> byClientId = new HashMap<>();          // "sender|clientMessageId"

    public Conversation(String id, Type type, String name) {
        this.id = id;
        this.type = type;
        this.name = name;
    }

    public String id() {
        return id;
    }

    public Type type() {
        return type;
    }

    public String name() {
        return name;
    }

    public long lastSeq() {
        return messages.size();
    }

    public Optional<Membership> member(String userId) {
        return Optional.ofNullable(members.get(userId));
    }

    public Collection<Membership> members() {
        return members.values();
    }

    public void addMember(Membership m) {
        members.put(m.userId(), m);
    }

    public void removeMember(String userId) {
        members.remove(userId);
    }

    public Message append(String senderId, String text, java.time.Instant at, Long replyTo, boolean system) {
        Message m = new Message(id, lastSeq() + 1, senderId, text, at, replyTo, system);
        messages.add(m);
        return m;
    }

    public Optional<Message> message(long seq) {
        return seq >= 1 && seq <= messages.size() ? Optional.of(messages.get((int) seq - 1)) : Optional.empty();
    }

    /** Messages with seq in (afterSeq, toSeq]. */
    public List<Message> range(long afterSeq, long toSeq) {
        int from = (int) Math.max(0, afterSeq);
        int to = (int) Math.min(messages.size(), toSeq);
        return from >= to ? List.of() : List.copyOf(messages.subList(from, to));
    }

    public Optional<Message> byClientId(String senderId, String clientMessageId) {
        return Optional.ofNullable(byClientId.get(senderId + "|" + clientMessageId));
    }

    public void rememberClientId(String senderId, String clientMessageId, Message m) {
        byClientId.put(senderId + "|" + clientMessageId, m);
    }
}
