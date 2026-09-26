package com.lld.social.network.feed;

import com.lld.social.network.model.Post;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

/** How the news feed is ordered (Strategy). Every order ends with the post sequence for stable paging. */
public interface FeedRanking {

    Comparator<Post> order(Instant now);

    /** Newest first. */
    static FeedRanking chronological() {
        return now -> Comparator.comparingLong(Post::sequence).reversed();
    }

    /**
     * "Top posts": engagement that decays with age, in the spirit of public news-site ranking formulas.
     * score = (1 + likes + 2 * comments) / (hours + 2)^1.5 — comments weigh more than likes, and a post
     * needs ever more engagement to stay on top as it gets older.
     */
    static FeedRanking engagement() {
        return now -> Comparator.comparingDouble((Post p) -> score(p, now)).reversed()
                .thenComparing(Comparator.comparingLong(Post::sequence).reversed());
    }

    static double score(Post p, Instant now) {
        double hours = Math.max(0, Duration.between(p.createdAt(), now).toMinutes() / 60.0);
        return (1 + p.likeCount() + 2.0 * p.comments().size()) / Math.pow(hours + 2, 1.5);
    }
}
