package com.lld.social.qa.service;

import java.time.Instant;

/** One line of a user's reputation history ("+10 upvote on A3"). The sum of these is the reputation. */
public record ReputationEvent(String userId, long delta, String reason, String postId, Instant at) {

    @Override
    public String toString() {
        return String.format("%+d %s (%s)", delta, reason, postId);
    }
}
