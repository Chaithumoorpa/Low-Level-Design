package com.lld.messaging.chat.model;

/** A refused action: not a member, not an admin, blocked, edit window over... */
public class ChatException extends RuntimeException {

    public ChatException(String message) {
        super(message);
    }
}
