package com.lld.social.network.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A status update. {@code sequence} is a global creation number used as a stable tie-breaker and cursor. */
public final class Post {

    private final String id;
    private final long sequence;
    private final String authorId;
    private final String text;
    private final Visibility visibility;
    private final Instant createdAt;
    private final Set<String> likes = new LinkedHashSet<>();
    private final List<Comment> comments = new ArrayList<>();

    public Post(String id, long sequence, String authorId, String text, Visibility visibility, Instant createdAt) {
        this.id = id;
        this.sequence = sequence;
        this.authorId = authorId;
        this.text = text;
        this.visibility = visibility;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public long sequence() {
        return sequence;
    }

    public String authorId() {
        return authorId;
    }

    public String text() {
        return text;
    }

    public Visibility visibility() {
        return visibility;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized Set<String> likes() {
        return Set.copyOf(likes);
    }

    public synchronized int likeCount() {
        return likes.size();
    }

    /** @return true if now liked, false if the like was removed */
    public synchronized boolean toggleLike(String userId) {
        if (likes.remove(userId)) {
            return false;
        }
        likes.add(userId);
        return true;
    }

    public synchronized List<Comment> comments() {
        return List.copyOf(comments);
    }

    public synchronized void addComment(Comment c) {
        comments.add(c);
    }

    @Override
    public String toString() {
        return id + " " + authorId + ": \"" + text + "\" [" + visibility + ", " + likeCount() + " likes, "
                + comments().size() + " comments]";
    }
}
