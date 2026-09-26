package com.lld.messaging.chat.model;

import java.time.Instant;

/** Stored message. Mutable only for edit/delete, and only under its conversation's lock. */
public final class Message {

    private final String conversationId;
    private final long seq;
    private final String senderId;
    private final Instant sentAt;
    private final Long replyToSeq;
    private final boolean system;
    private String text;
    private boolean edited;
    private boolean deleted;

    public Message(String conversationId, long seq, String senderId, String text, Instant sentAt, Long replyToSeq,
                   boolean system) {
        this.conversationId = conversationId;
        this.seq = seq;
        this.senderId = senderId;
        this.text = text;
        this.sentAt = sentAt;
        this.replyToSeq = replyToSeq;
        this.system = system;
    }

    public long seq() {
        return seq;
    }

    public String senderId() {
        return senderId;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public boolean system() {
        return system;
    }

    public boolean deleted() {
        return deleted;
    }

    public String text() {
        return text;
    }

    public void edit(String newText) {
        text = newText;
        edited = true;
    }

    /** Tombstone: the seq stays (so clients keep their place), the content goes. */
    public void delete() {
        text = "";
        deleted = true;
    }

    public MessageView view() {
        return new MessageView(conversationId, seq, senderId, text, sentAt, replyToSeq, edited, deleted, system);
    }
}
