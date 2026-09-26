package com.lld.messaging.chat.model;

/** What the server pushes down a device's connection (think: one WebSocket frame each). */
public sealed interface ChatEvent {

    String conversationId();

    /** A new message. {@code silent}: the conversation is muted, show it but don't ring. */
    record NewMessage(String conversationId, MessageView message, boolean silent) implements ChatEvent {
    }

    /** A message was edited or deleted: replace it in place (same seq). */
    record MessageUpdated(String conversationId, MessageView message) implements ChatEvent {
    }

    /** A member's delivered/read pointers moved (ticks for senders, badge sync for the reader's other devices). */
    record Receipt(String conversationId, String userId, long deliveredUpTo, long readUpTo) implements ChatEvent {
    }

    /** Ephemeral: never stored, never synced later. */
    record Typing(String conversationId, String userId) implements ChatEvent {
    }
}
