package com.lld.social.qa.service;

import com.lld.social.qa.model.Answer;
import com.lld.social.qa.model.Post;
import com.lld.social.qa.model.VoteType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who gains or loses how much reputation for each vote (Strategy: sites tune these numbers).
 * A vote's effect is a map user → delta, so undoing a vote simply applies the negated map.
 */
public class ReputationPolicy {

    public long questionUpvote() {
        return 10;
    }

    public long answerUpvote() {
        return 10;
    }

    /** Received by the author of a downvoted post. */
    public long downvoteReceived() {
        return -2;
    }

    /** Paid by whoever downvotes an <em>answer</em> (so downvotes aren't free). */
    public long answerDownvoteCost() {
        return -1;
    }

    public long acceptedAnswer() {
        return 15;
    }

    public long acceptingAnswer() {
        return 2;
    }

    public Map<String, Long> effectOfVote(Post post, String voterId, VoteType vote) {
        Map<String, Long> effect = new LinkedHashMap<>();
        boolean answer = post instanceof Answer;
        if (vote == VoteType.UP) {
            effect.put(post.authorId(), answer ? answerUpvote() : questionUpvote());
        } else {
            effect.put(post.authorId(), downvoteReceived());
            if (answer) {
                effect.merge(voterId, answerDownvoteCost(), Long::sum);
            }
        }
        return effect;
    }
}
