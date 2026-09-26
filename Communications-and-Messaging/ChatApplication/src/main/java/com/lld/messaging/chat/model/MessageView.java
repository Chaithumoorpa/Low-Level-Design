package com.lld.messaging.chat.model;

import java.time.Instant;

/**
 * Immutable snapshot of a message as a client sees it. {@code seq} is the message's position in its
 * conversation (1, 2, 3...): clients sort, deduplicate and sync by it.
 */
public record MessageView(String conversationId, long seq, String senderId, String text, Instant sentAt,
                          Long replyToSeq, boolean edited, boolean deleted, boolean system) {

    @Override
    public String toString() {
        String who = system ? "*" : senderId + ":";
        String body = deleted ? "(message deleted)" : text + (edited ? " (edited)" : "");
        return "#" + seq + " " + who + " " + body + (replyToSeq == null ? "" : " [reply to #" + replyToSeq + "]");
    }
}
