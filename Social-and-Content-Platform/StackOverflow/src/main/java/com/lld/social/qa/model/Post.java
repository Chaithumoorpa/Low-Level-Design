package com.lld.social.qa.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What questions and answers have in common: an author, a body with edit history, comments, and votes
 * (at most one per user, changeable). Score = up votes − down votes.
 */
public abstract class Post {

    private final String id;
    private final String authorId;
    private final Instant createdAt;
    private final Map<String, VoteType> votes = new HashMap<>();
    private final List<Comment> comments = new ArrayList<>();
    private final List<Revision> revisions = new ArrayList<>();
    private Instant lastActivity;

    protected Post(String id, String authorId, String title, String body, Instant at) {
        this.id = id;
        this.authorId = authorId;
        this.createdAt = at;
        this.lastActivity = at;
        revisions.add(new Revision(1, authorId, title, body, "original", at));
    }

    public String id() {
        return id;
    }

    public String authorId() {
        return authorId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized Instant lastActivity() {
        return lastActivity;
    }

    public synchronized void touch(Instant at) {
        if (at.isAfter(lastActivity)) {
            lastActivity = at;
        }
    }

    public synchronized String body() {
        return revisions.get(revisions.size() - 1).body();
    }

    protected synchronized String currentTitle() {
        return revisions.get(revisions.size() - 1).title();
    }

    public synchronized List<Revision> revisions() {
        return List.copyOf(revisions);
    }

    public synchronized void edit(String editorId, String title, String body, String summary, Instant at) {
        revisions.add(new Revision(revisions.size() + 1, editorId, title, body, summary, at));
        touch(at);
    }

    public synchronized int score() {
        return votes.values().stream().mapToInt(VoteType::value).sum();
    }

    public synchronized Optional<VoteType> voteOf(String userId) {
        return Optional.ofNullable(votes.get(userId));
    }

    /** @return the previous vote (empty if none); {@code vote == null} removes the vote */
    public synchronized Optional<VoteType> setVote(String userId, VoteType vote) {
        return Optional.ofNullable(vote == null ? votes.remove(userId) : votes.put(userId, vote));
    }

    public synchronized List<Comment> comments() {
        return List.copyOf(comments);
    }

    public synchronized void addComment(Comment c) {
        comments.add(c);
        touch(c.at());
    }

    /** The question this post belongs to (itself for a question). */
    public abstract String questionId();
}
