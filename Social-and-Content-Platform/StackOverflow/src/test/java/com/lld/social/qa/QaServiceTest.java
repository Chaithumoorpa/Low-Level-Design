package com.lld.social.qa;

import com.lld.social.qa.model.Answer;
import com.lld.social.qa.model.QaException;
import com.lld.social.qa.model.Question;
import com.lld.social.qa.model.VoteType;
import com.lld.social.qa.service.ManualClock;
import com.lld.social.qa.service.QaService;
import com.lld.social.qa.service.ReputationEvent;
import com.lld.social.qa.service.ReputationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaServiceTest {

    private ManualClock clock;
    private QaService site;
    private Question q;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2027-06-01T09:00:00Z"));
        site = new QaService(new ReputationPolicy(), clock);
        for (String u : List.of("asker", "ans1", "ans2", "voter", "newbie")) {
            site.register(u, u);
        }
        site.importReputation("voter", 5000, "seed");
        site.importReputation("ans1", 199, "seed");                // 200
        site.importReputation("ans2", 199, "seed");
        q = site.ask("asker", "How to reverse a linked list?", "Iteratively please.", "java", "algorithms");
    }

    private long rep(String u) {
        return site.user(u).reputation();
    }

    private long sumOfHistory(String u) {
        return site.reputationHistory(u).stream().mapToLong(ReputationEvent::delta).sum();
    }

    // ------------------------------------------------------------------ posting rules

    @Nested
    class Posting {

        @Test
        void questionValidation() {
            assertThrows(QaException.class, () -> site.ask("asker", "short", "b", "java"));
            assertThrows(QaException.class, () -> site.ask("asker", "A proper long title", "b"));
            assertThrows(QaException.class, () -> site.ask("asker", "A proper long title", "b", "a", "b", "c", "d", "e", "f"));
            assertEquals(Set.of("java", "algorithms"), q.tags());
            assertEquals(List.of("java", "algorithms"), List.copyOf(q.tags()), "order kept");
            assertThrows(QaException.class, () -> site.answer("asker", q.id(), "  "));
        }

        @Test
        void commentsNeedReputationOutsideYourOwnThread() {
            Answer a = site.answer("ans1", q.id(), "Three pointers.");
            site.comment("asker", a.id(), "Thanks");                  // answer on own question
            site.comment("newbie", site.answer("newbie", q.id(), "Recursion").id(), "my own answer");
            assertThrows(QaException.class, () -> site.comment("newbie", a.id(), "hi"));
            site.comment("ans2", a.id(), "ans2 has 200 rep");
            assertEquals(2, a.comments().size());
        }

        @Test
        void editsKeepRevisionsAndNeedPermission() {
            Answer a = site.answer("ans1", q.id(), "v1");
            site.edit("ans1", a.id(), null, "v2", "typo");
            assertThrows(QaException.class, () -> site.edit("ans2", a.id(), null, "vandal", "x"));
            site.edit("voter", a.id(), null, "v3", "formatting");      // 5000 rep
            assertEquals(List.of("v1", "v2", "v3"), a.revisions().stream().map(r -> r.body()).toList());
            assertEquals("v3", a.body());
        }

        @Test
        void uniqueViews() {
            site.view("x", q.id());
            site.view("x", q.id());
            site.view("y", q.id());
            assertEquals(2, q.views());
        }
    }

    // ------------------------------------------------------------------ voting & reputation

    @Nested
    class Voting {

        @Test
        void privilegesAndOwnPosts() {
            Answer a = site.answer("ans1", q.id(), "x");
            assertThrows(QaException.class, () -> site.vote("newbie", a.id(), VoteType.UP), "1 rep");
            assertThrows(QaException.class, () -> site.vote("ans1", a.id(), VoteType.UP), "own post");
            site.register("mid", "mid");
            site.importReputation("mid", 99, "seed");                  // 100: can upvote, not downvote
            site.vote("mid", a.id(), VoteType.UP);
            assertThrows(QaException.class, () -> site.vote("mid", a.id(), VoteType.DOWN), "downvote needs 125");
            site.vote("ans2", a.id(), VoteType.DOWN);                  // 200 rep is enough
        }

        @Test
        void upvotesDownvotesAndTheirCosts() {
            Answer a = site.answer("ans1", q.id(), "x");
            site.vote("voter", q.id(), VoteType.UP);
            assertEquals(11, rep("asker"));
            site.vote("voter", a.id(), VoteType.DOWN);
            assertEquals(198, rep("ans1"));
            assertEquals(5000, rep("voter"), "5001 - 1 for downvoting an answer");
            site.vote("voter", q.id(), VoteType.DOWN);                 // switch on a question: no voter cost
            assertEquals(1, rep("asker"), "1 + 10 - 10 - 2 floors at 1");
            assertEquals(-1, q.score());
        }

        @Test
        void undoAndSwitchAreExactlyReversible() {
            Answer a = site.answer("ans1", q.id(), "x");
            long ansBefore = rep("ans1");
            long voterBefore = rep("voter");
            site.vote("voter", a.id(), VoteType.UP);
            site.vote("voter", a.id(), VoteType.DOWN);
            site.vote("voter", a.id(), VoteType.UP);
            site.vote("voter", a.id(), VoteType.UP);                   // undo
            assertEquals(ansBefore, rep("ans1"));
            assertEquals(voterBefore, rep("voter"));
            assertEquals(0, a.score());
            assertEquals(rep("ans1") - 1, sumOfHistory("ans1"), "reputation = 1 + sum of history");
        }

        @Test
        void acceptingMovesTheBonus() {
            Answer a1 = site.answer("ans1", q.id(), "x");
            Answer a2 = site.answer("ans2", q.id(), "y");
            assertThrows(QaException.class, () -> site.accept("ans1", a1.id()), "only the asker");
            site.accept("asker", a1.id());
            assertEquals(215, rep("ans1"));
            assertEquals(3, rep("asker"));
            site.accept("asker", a2.id());
            assertEquals(200, rep("ans1"));
            assertEquals(215, rep("ans2"));
            assertEquals(3, rep("asker"), "the +2 moved, not doubled");
            site.accept("asker", a2.id());                             // no-op
            assertEquals(215, rep("ans2"));
            assertEquals(List.of(a2, a1), site.answersOf(q.id()), "accepted first");
        }

        @Test
        void acceptingYourOwnAnswerEarnsNothing() {
            Answer own = site.answer("asker", q.id(), "Found it myself");
            site.accept("asker", own.id());
            assertEquals(1, rep("asker"));
        }

        @Test
        void answersSortByAcceptedThenScoreThenAge() {
            Answer old = site.answer("ans1", q.id(), "a");
            clock.advance(Duration.ofMinutes(1));
            Answer popular = site.answer("ans2", q.id(), "b");
            clock.advance(Duration.ofMinutes(1));
            Answer young = site.answer("newbie", q.id(), "c");
            site.vote("voter", popular.id(), VoteType.UP);
            assertEquals(List.of(popular, old, young), site.answersOf(q.id()));
        }
    }

    // ------------------------------------------------------------------ moderation & bounty

    @Nested
    class Moderation {

        @Test
        void closingNeedsThreeTrustedVotes() {
            for (String m : List.of("m1", "m2", "m3")) {
                site.register(m, m);
                site.importReputation(m, 3000, "seed");
            }
            assertThrows(QaException.class, () -> site.voteToClose("ans1", q.id(), "dup"));
            assertFalse(site.voteToClose("m1", q.id(), "dup"));
            assertThrows(QaException.class, () -> site.voteToClose("m1", q.id(), "dup"), "one vote each");
            assertFalse(site.voteToClose("m2", q.id(), "dup"));
            assertTrue(site.voteToClose("m3", q.id(), "dup"));
            assertTrue(q.isClosed());
            assertThrows(QaException.class, () -> site.answer("ans1", q.id(), "late"));
            assertThrows(QaException.class, () -> site.voteToClose("voter", q.id(), "dup"));
        }

        @Test
        void bountyLifecycle() {
            site.importReputation("asker", 200, "seed");                // 201
            assertThrows(QaException.class, () -> site.offerBounty("asker", q.id(), 40), "min 50");
            assertThrows(QaException.class, () -> site.offerBounty("ans1", q.id(), 50), "not the asker");
            site.offerBounty("asker", q.id(), 100);
            assertEquals(101, rep("asker"));
            assertThrows(QaException.class, () -> site.offerBounty("asker", q.id(), 50), "one at a time");
            Answer a = site.answer("ans1", q.id(), "x");
            Answer own = site.answer("asker", q.id(), "mine");
            assertThrows(QaException.class, () -> site.awardBounty("asker", own.id()));
            site.awardBounty("asker", a.id());
            assertEquals(300, rep("ans1"));
            assertThrows(QaException.class, () -> site.awardBounty("asker", a.id()), "already awarded");
        }

        @Test
        void bountyExpires() {
            site.importReputation("asker", 200, "seed");
            site.offerBounty("asker", q.id(), 50);
            Answer a = site.answer("ans1", q.id(), "x");
            clock.advance(Duration.ofDays(7).plusSeconds(1));
            assertThrows(QaException.class, () -> site.awardBounty("asker", a.id()));
        }
    }

    // ------------------------------------------------------------------ search

    @Test
    void searchByWordsTagsAndSort() {
        clock.advance(Duration.ofMinutes(1));
        Question q2 = site.ask("ans1", "Reverse a string in Java", "StringBuilder?", "java", "strings");
        clock.advance(Duration.ofMinutes(1));
        Question q3 = site.ask("ans2", "Linked list cycle detection", "Floyd", "algorithms");
        site.vote("voter", q3.id(), VoteType.UP);
        assertEquals(List.of(q2, q), site.search("reverse", Set.of(), false, QaService.Sort.NEWEST));
        assertEquals(List.of(q), site.search("reverse list", Set.of(), false, QaService.Sort.VOTES), "all words");
        assertEquals(List.of(q3, q), site.search("", Set.of("ALGORITHMS"), false, QaService.Sort.VOTES));
        Answer a = site.answer("ans1", q3.id(), "Floyd");
        site.vote("voter", a.id(), VoteType.UP);
        assertEquals(List.of(q), site.search("", Set.of("algorithms"), true, QaService.Sort.NEWEST));
        clock.advance(Duration.ofMinutes(1));
        site.comment("ans2", q.id(), "bump");
        assertEquals(q, site.search("", Set.of(), false, QaService.Sort.ACTIVE).get(0), "comment = activity");
        site.edit("asker", q.id(), "How to reverse a doubly linked list?", q.body(), "clarify");
        assertEquals(List.of(q), site.search("doubly", Set.of(), false, QaService.Sort.VOTES), "re-indexed after edit");
    }

    // ------------------------------------------------------------------ properties & concurrency

    @Test
    void randomVotingKeepsReputationEqualToHistory() {
        List<String> voters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            site.register("v" + i, "v" + i);
            site.importReputation("v" + i, 500, "seed");
            voters.add("v" + i);
        }
        List<String> postIds = new ArrayList<>(List.of(q.id()));
        postIds.add(site.answer("ans1", q.id(), "a").id());
        postIds.add(site.answer("ans2", q.id(), "b").id());
        Random r = new Random(5);
        for (int i = 0; i < 2000; i++) {
            site.vote(voters.get(r.nextInt(10)), postIds.get(r.nextInt(3)), r.nextBoolean() ? VoteType.UP : VoteType.DOWN);
        }
        for (String u : List.of("asker", "ans1", "ans2", "v0", "v9")) {
            assertEquals(Math.max(1, 1 + sumOfHistory(u)), rep(u), u);
        }
        long expectedScore = voters.stream().mapToLong(v -> q.voteOf(v).map(VoteType::value).orElse(0)).sum();
        assertEquals(expectedScore, q.score());
    }

    @Test
    void concurrentVotesAreAllCounted() throws Exception {
        Answer a = site.answer("ans1", q.id(), "x");
        List<String> voters = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            site.register("c" + i, "c" + i);
            site.importReputation("c" + i, 20, "seed");
            voters.add("c" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (String v : voters) {
            futures.add(pool.submit(() -> {
                start.await();
                return site.vote(v, a.id(), VoteType.UP);
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(100, a.score());
        assertEquals(200 + 100 * 10, rep("ans1"));
    }
}
