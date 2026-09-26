package com.lld.social.qa.model;

/** A refused action: missing privilege, voting on your own post, question closed... */
public class QaException extends RuntimeException {

    public QaException(String message) {
        super(message);
    }
}
