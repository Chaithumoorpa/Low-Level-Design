package com.lld.social.learning.model;

/** A refused action: prerequisites missing, lesson locked, out of quiz attempts, refund window over... */
public class LearningException extends RuntimeException {

    public LearningException(String message) {
        super(message);
    }
}
