package com.lld.social.qa.model;

import java.time.Instant;

public final class Answer extends Post {

    private final String questionId;

    public Answer(String id, String questionId, String authorId, String body, Instant at) {
        super(id, authorId, null, body, at);
        this.questionId = questionId;
    }

    @Override
    public String questionId() {
        return questionId;
    }

    @Override
    public String toString() {
        return id() + " by " + authorId() + " score " + score();
    }
}
