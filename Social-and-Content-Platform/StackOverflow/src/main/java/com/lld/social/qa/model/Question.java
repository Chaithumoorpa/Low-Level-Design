package com.lld.social.qa.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A question with its answers. Closing takes several votes from trusted users; an accepted answer is
 * chosen by the asker; a bounty is reputation the asker puts up to attract answers.
 */
public final class Question extends Post {

    public static final int CLOSE_VOTES_NEEDED = 3;

    private final Set<String> tags;
    private final List<Answer> answers = new ArrayList<>();
    private final Set<String> closeVotes = new LinkedHashSet<>();
    private final Set<String> viewers = new HashSet<>();
    private String acceptedAnswerId;
    private String closeReason;
    private long bounty;
    private Instant bountyEndsAt;

    public Question(String id, String authorId, String title, String body, Set<String> tags, Instant at) {
        super(id, authorId, title, body, at);
        this.tags = new LinkedHashSet<>(tags);
    }

    @Override
    public String questionId() {
        return id();
    }

    public String title() {
        return currentTitle();
    }

    public synchronized Set<String> tags() {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(tags));   // as the asker typed them
    }

    public synchronized List<Answer> answers() {
        return List.copyOf(answers);
    }

    public synchronized void addAnswer(Answer a) {
        answers.add(a);
        touch(a.createdAt());
    }

    public synchronized String acceptedAnswerId() {
        return acceptedAnswerId;
    }

    public synchronized void setAcceptedAnswerId(String answerId) {
        acceptedAnswerId = answerId;
    }

    public synchronized boolean isClosed() {
        return closeReason != null;
    }

    public synchronized String closeReason() {
        return closeReason;
    }

    /** @return true if this vote closed the question */
    public synchronized boolean voteToClose(String userId, String reason) {
        if (!closeVotes.add(userId)) {
            throw new QaException(userId + " already voted to close");
        }
        if (closeVotes.size() >= CLOSE_VOTES_NEEDED) {
            closeReason = reason;
            return true;
        }
        return false;
    }

    public synchronized int closeVotes() {
        return closeVotes.size();
    }

    public synchronized int views() {
        return viewers.size();
    }

    public synchronized void view(String userId) {
        viewers.add(userId);
    }

    public synchronized long bounty() {
        return bounty;
    }

    public synchronized Instant bountyEndsAt() {
        return bountyEndsAt;
    }

    public synchronized void setBounty(long amount, Instant endsAt) {
        bounty = amount;
        bountyEndsAt = endsAt;
    }

    @Override
    public String toString() {
        return String.format("%s [%+d] %s %s answers=%d%s%s", id(), score(), title(), tags(), answers().size(),
                acceptedAnswerId() == null ? "" : " accepted=" + acceptedAnswerId(), isClosed() ? " CLOSED" : "");
    }
}
