package com.lld.social.network.model;

/** A refused action: blocked, not visible, duplicate request, not your post... */
public class SocialException extends RuntimeException {

    public SocialException(String message) {
        super(message);
    }
}
