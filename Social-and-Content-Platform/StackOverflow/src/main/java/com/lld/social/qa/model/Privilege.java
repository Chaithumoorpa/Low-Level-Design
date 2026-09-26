package com.lld.social.qa.model;

/**
 * What reputation unlocks. Earned trust instead of roles: new users can ask and answer, but moderation
 * powers come with a track record. (Thresholds are illustrative.)
 */
public enum Privilege {
    UPVOTE(15),
    COMMENT_ANYWHERE(50),
    OFFER_BOUNTY(75),
    DOWNVOTE(125),
    EDIT_OTHERS(2000),
    VOTE_TO_CLOSE(3000);

    private final long minReputation;

    Privilege(long minReputation) {
        this.minReputation = minReputation;
    }

    public long minReputation() {
        return minReputation;
    }
}
