package com.lld.messaging.chat.server;

import com.lld.messaging.chat.model.ChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One connected device (phone, laptop). Stands in for a WebSocket: the server pushes events into it,
 * and it remembers the highest seq it has seen per conversation so a reconnect can ask for "everything
 * after #n" instead of the whole history.
 */
public final class Session {

    private final String userId;
    private final String deviceId;
    private final List<ChatEvent> inbox = new ArrayList<>();
    private final Map<String, Long> lastSeq;                    // per-device cursor, kept by the server
    private volatile boolean open = true;

    Session(String userId, String deviceId, Map<String, Long> cursor) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.lastSeq = cursor;
    }

    public String userId() {
        return userId;
    }

    public String deviceId() {
        return deviceId;
    }

    public boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
    }

    synchronized void push(ChatEvent event) {
        inbox.add(event);
        if (event instanceof ChatEvent.NewMessage m) {
            lastSeq.merge(m.conversationId(), m.message().seq(), Math::max);
        }
    }

    synchronized long lastSeq(String conversationId) {
        return lastSeq.getOrDefault(conversationId, 0L);
    }

    synchronized void seen(String conversationId, long seq) {
        lastSeq.merge(conversationId, seq, Math::max);
    }

    /** Takes everything received so far (what the app would render). */
    public synchronized List<ChatEvent> drain() {
        List<ChatEvent> out = List.copyOf(inbox);
        inbox.clear();
        return out;
    }

    public synchronized List<ChatEvent> peek() {
        return List.copyOf(inbox);
    }

    @Override
    public String toString() {
        return userId + "@" + deviceId;
    }
}
