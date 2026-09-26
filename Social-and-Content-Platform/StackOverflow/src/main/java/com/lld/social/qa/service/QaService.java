package com.lld.social.qa.service;

import com.lld.social.qa.model.Answer;
import com.lld.social.qa.model.Comment;
import com.lld.social.qa.model.Post;
import com.lld.social.qa.model.Privilege;
import com.lld.social.qa.model.QaException;
import com.lld.social.qa.model.Question;
import com.lld.social.qa.model.User;
import com.lld.social.qa.model.VoteType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Facade for the Q&amp;A site. Every write checks privileges and business rules, then updates posts and
 * reputation together under one lock, so a vote can never be counted without its reputation effect
 * (or vice versa). Reputation changes are also appended to a per-user history.
 */
public final class QaService {

    public enum Sort {
        VOTES, NEWEST, ACTIVE
    }

    public static final int MAX_TAGS = 5;
    public static final Duration BOUNTY_PERIOD = Duration.ofDays(7);

    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Question> questions = new LinkedHashMap<>();
    private final Map<String, Post> posts = new HashMap<>();
    private final Map<String, List<ReputationEvent>> history = new HashMap<>();
    private final Map<String, Set<String>> wordIndex = new HashMap<>();       // word -> question ids
    private final Map<String, Set<String>> tagIndex = new HashMap<>();        // tag -> question ids
    private final ReputationPolicy rep;
    private final Clock clock;
    private long questionSeq;
    private long answerSeq;
    private long commentSeq;

    public QaService(ReputationPolicy rep, Clock clock) {
        this.rep = Objects.requireNonNull(rep);
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ users

    public synchronized User register(String id, String name) {
        if (users.containsKey(id)) {
            throw new QaException("User " + id + " exists");
        }
        User u = new User(id, name);
        users.put(id, u);
        return u;
    }

    /** Reputation brought in from elsewhere (like an "association bonus" for a linked account). */
    public synchronized void importReputation(String userId, long amount, String reason) {
        if (amount <= 0) {
            throw new QaException("Imported reputation must be positive");
        }
        user(userId);
        apply(Map.of(userId, amount), reason, "-");
    }

    // ------------------------------------------------------------------ posting

    public synchronized Question ask(String userId, String title, String body, String... tags) {
        user(userId);
        if (title == null || title.strip().length() < 10) {
            throw new QaException("Title must be at least 10 characters");
        }
        Set<String> tagSet = Arrays.stream(tags).map(t -> t.toLowerCase(Locale.ROOT).strip())
                .filter(t -> !t.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (tagSet.isEmpty() || tagSet.size() > MAX_TAGS) {
            throw new QaException("Use 1 to " + MAX_TAGS + " tags");
        }
        Question q = new Question("Q" + (++questionSeq), userId, title.strip(), body, tagSet, now());
        questions.put(q.id(), q);
        posts.put(q.id(), q);
        index(q);
        return q;
    }

    public synchronized Answer answer(String userId, String questionId, String body) {
        user(userId);
        Question q = question(questionId);
        if (q.isClosed()) {
            throw new QaException(questionId + " is closed: " + q.closeReason());
        }
        if (body == null || body.isBlank()) {
            throw new QaException("Empty answer");
        }
        Answer a = new Answer("A" + (++answerSeq), questionId, userId, body, now());
        q.addAnswer(a);
        posts.put(a.id(), a);
        return a;
    }

    /** Anyone may comment on their own posts and on answers to their question; elsewhere it takes 50 rep. */
    public synchronized Comment comment(String userId, String postId, String text) {
        User u = user(userId);
        Post p = post(postId);
        boolean ownThread = p.authorId().equals(userId) || question(p.questionId()).authorId().equals(userId);
        if (!ownThread && !u.can(Privilege.COMMENT_ANYWHERE)) {
            throw new QaException(u.name() + " needs " + Privilege.COMMENT_ANYWHERE.minReputation() + " reputation to comment here");
        }
        Comment c = new Comment("C" + (++commentSeq), userId, text, now());
        p.addComment(c);
        question(p.questionId()).touch(c.at());
        return c;
    }

    /** The author, or anyone with the edit privilege; every edit is kept as a revision. */
    public synchronized void edit(String userId, String postId, String newTitle, String newBody, String summary) {
        User u = user(userId);
        Post p = post(postId);
        if (!p.authorId().equals(userId) && !u.can(Privilege.EDIT_OTHERS)) {
            throw new QaException(u.name() + " can only edit their own posts");
        }
        String title = p instanceof Question q ? (newTitle == null ? q.title() : newTitle) : null;
        p.edit(userId, title, newBody, summary, now());
        if (p instanceof Question q) {
            index(q);
        }
        question(p.questionId()).touch(now());
    }

    public synchronized void view(String userId, String questionId) {
        question(questionId).view(userId);
    }

    // ------------------------------------------------------------------ voting

    /**
     * Casting the same vote again removes it; casting the opposite vote switches it. The old vote's
     * reputation effect is reversed before the new one is applied.
     */
    public synchronized int vote(String userId, String postId, VoteType vote) {
        User voter = user(userId);
        Post p = post(postId);
        if (p.authorId().equals(userId)) {
            throw new QaException("You can't vote on your own post");
        }
        Privilege needed = vote == VoteType.UP ? Privilege.UPVOTE : Privilege.DOWNVOTE;
        if (!voter.can(needed)) {
            throw new QaException(voter.name() + " needs " + needed.minReputation() + " reputation to vote " + vote);
        }
        VoteType previous = p.voteOf(userId).orElse(null);
        VoteType next = previous == vote ? null : vote;
        if (previous != null) {
            apply(negate(rep.effectOfVote(p, userId, previous)), "vote " + previous + " undone", p.id());
        }
        if (next != null) {
            apply(rep.effectOfVote(p, userId, next), next == VoteType.UP ? "upvote" : "downvote", p.id());
        }
        p.setVote(userId, next);
        return p.score();
    }

    /** Only the asker; switching the accepted answer moves the bonus. Accepting your own answer earns nothing. */
    public synchronized void accept(String userId, String answerId) {
        Post p = post(answerId);
        if (!(p instanceof Answer a)) {
            throw new QaException(answerId + " is not an answer");
        }
        Question q = question(a.questionId());
        if (!q.authorId().equals(userId)) {
            throw new QaException("Only the asker can accept an answer");
        }
        String previous = q.acceptedAnswerId();
        if (answerId.equals(previous)) {
            return;
        }
        if (previous != null) {
            apply(negate(acceptEffect(q, (Answer) post(previous))), "accept undone", previous);
        }
        apply(acceptEffect(q, a), "accepted answer", a.id());
        q.setAcceptedAnswerId(a.id());
    }

    private Map<String, Long> acceptEffect(Question q, Answer a) {
        Map<String, Long> effect = new LinkedHashMap<>();
        if (!a.authorId().equals(q.authorId())) {
            effect.put(a.authorId(), rep.acceptedAnswer());
            effect.put(q.authorId(), rep.acceptingAnswer());
        }
        return effect;
    }

    // ------------------------------------------------------------------ moderation & bounties

    public synchronized boolean voteToClose(String userId, String questionId, String reason) {
        User u = user(userId);
        Question q = question(questionId);
        if (!u.can(Privilege.VOTE_TO_CLOSE)) {
            throw new QaException(u.name() + " needs " + Privilege.VOTE_TO_CLOSE.minReputation() + " reputation to vote to close");
        }
        if (q.isClosed()) {
            throw new QaException(questionId + " is already closed");
        }
        return q.voteToClose(userId, reason);
    }

    /** The asker puts up reputation (paid now) to draw attention for 7 days. */
    public synchronized void offerBounty(String userId, String questionId, long amount) {
        User u = user(userId);
        Question q = question(questionId);
        if (!q.authorId().equals(userId)) {
            throw new QaException("Only the asker can offer a bounty here");
        }
        if (!u.can(Privilege.OFFER_BOUNTY) || amount < 50 || amount > 500 || u.reputation() - amount < 1) {
            throw new QaException("Bounty must be 50-500 and affordable (needs " + Privilege.OFFER_BOUNTY.minReputation() + " rep)");
        }
        if (q.bounty() > 0 || q.isClosed()) {
            throw new QaException(questionId + " can't take a bounty now");
        }
        apply(Map.of(userId, -amount), "bounty offered", questionId);
        q.setBounty(amount, now().plus(BOUNTY_PERIOD));
    }

    public synchronized void awardBounty(String userId, String answerId) {
        Post p = post(answerId);
        Question q = question(p.questionId());
        if (!q.authorId().equals(userId) || q.bounty() == 0) {
            throw new QaException("No bounty of yours to award on " + q.id());
        }
        if (p.authorId().equals(userId)) {
            throw new QaException("You can't award your own bounty to yourself");
        }
        if (now().isAfter(q.bountyEndsAt())) {
            throw new QaException("The bounty period has ended");
        }
        apply(Map.of(p.authorId(), q.bounty()), "bounty awarded", answerId);
        q.setBounty(0, null);
    }

    // ------------------------------------------------------------------ search

    /** All words must match (title or body); optional tag filter; unanswered = no answer with score > 0. */
    public synchronized List<Question> search(String words, Set<String> tags, boolean unansweredOnly, Sort sort) {
        Set<String> ids = null;
        for (String w : tokens(words == null ? "" : words)) {
            ids = intersect(ids, wordIndex.getOrDefault(w, Set.of()));
        }
        for (String t : tags) {
            ids = intersect(ids, tagIndex.getOrDefault(t.toLowerCase(Locale.ROOT), Set.of()));
        }
        List<Question> out = new ArrayList<>((ids == null ? questions.keySet() : ids).stream().map(questions::get).toList());
        if (unansweredOnly) {
            out.removeIf(q -> q.answers().stream().anyMatch(a -> a.score() > 0) || q.acceptedAnswerId() != null);
        }
        Comparator<Question> order = switch (sort) {
            case VOTES -> Comparator.comparingInt(Question::score).reversed();
            case NEWEST -> Comparator.comparing(Question::createdAt).reversed();
            case ACTIVE -> Comparator.comparing(Question::lastActivity).reversed();
        };
        out.sort(order.thenComparing(Question::id));
        return out;
    }

    /** Answers as shown on the page: accepted first, then by score, then oldest. */
    public synchronized List<Answer> answersOf(String questionId) {
        Question q = question(questionId);
        List<Answer> out = new ArrayList<>(q.answers());
        out.sort(Comparator.comparing((Answer a) -> !a.id().equals(q.acceptedAnswerId()))
                .thenComparing(Comparator.comparingInt(Answer::score).reversed())
                .thenComparing(Answer::createdAt));
        return out;
    }

    public synchronized List<ReputationEvent> reputationHistory(String userId) {
        user(userId);
        return List.copyOf(history.getOrDefault(userId, List.of()));
    }

    public synchronized User user(String id) {
        User u = users.get(id);
        if (u == null) {
            throw new QaException("No user " + id);
        }
        return u;
    }

    public synchronized Question question(String id) {
        Question q = questions.get(id);
        if (q == null) {
            throw new QaException("No question " + id);
        }
        return q;
    }

    public synchronized Post post(String id) {
        Post p = posts.get(id);
        if (p == null) {
            throw new QaException("No post " + id);
        }
        return p;
    }

    // ------------------------------------------------------------------ internals

    private void apply(Map<String, Long> effect, String reason, String postId) {
        effect.forEach((userId, delta) -> {
            user(userId).addReputation(delta);
            history.computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(new ReputationEvent(userId, delta, reason, postId, now()));
        });
    }

    private static Map<String, Long> negate(Map<String, Long> effect) {
        Map<String, Long> out = new LinkedHashMap<>();
        effect.forEach((k, v) -> out.put(k, -v));
        return out;
    }

    private void index(Question q) {
        wordIndex.values().forEach(s -> s.remove(q.id()));                 // re-index after edits
        for (String w : tokens(q.title() + " " + q.body())) {
            wordIndex.computeIfAbsent(w, k -> new HashSet<>()).add(q.id());
        }
        for (String t : q.tags()) {
            tagIndex.computeIfAbsent(t, k -> new HashSet<>()).add(q.id());
        }
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}#+]+")) {
            if (t.length() > 1) {
                out.add(t);
            }
        }
        return out;
    }

    private static Set<String> intersect(Set<String> acc, Set<String> next) {
        if (acc == null) {
            return new HashSet<>(next);
        }
        acc.retainAll(next);
        return acc;
    }

    private Instant now() {
        return clock.instant();
    }
}
