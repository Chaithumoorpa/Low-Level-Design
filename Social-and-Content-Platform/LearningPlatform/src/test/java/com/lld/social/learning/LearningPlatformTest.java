package com.lld.social.learning;

import com.lld.social.learning.model.Certificate;
import com.lld.social.learning.model.Course;
import com.lld.social.learning.model.Enrollment;
import com.lld.social.learning.model.LearningException;
import com.lld.social.learning.model.Lesson;
import com.lld.social.learning.model.Lesson.QuizQuestion;
import com.lld.social.learning.payment.FakePayments;
import com.lld.social.learning.service.LearningPlatform;
import com.lld.social.learning.service.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningPlatformTest {

    private ManualClock clock;
    private FakePayments payments;
    private LearningPlatform lp;
    private final List<Certificate> issued = new ArrayList<>();
    private Course free;
    private Course paid;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2027-08-01T09:00:00Z"));
        payments = new FakePayments();
        lp = new LearningPlatform(payments, clock, "test-secret");
        lp.onCompletion(issued::add);
        for (String u : List.of("ins", "s1", "s2", "s3")) {
            lp.register(u, u);
        }
        free = lp.createCourse("ins", "Intro to Java", "Programming", 0, true);
        lp.addModule("ins", free.id(), "M1");
        lp.addLesson("ins", free.id(), 0, new Lesson.Video("f1", "Video", 10));
        lp.addLesson("ins", free.id(), 0, new Lesson.Quiz("fq", "Quiz", List.of(
                new QuizQuestion("q1", List.of("a", "b"), 1),
                new QuizQuestion("q2", List.of("a", "b"), 0),
                new QuizQuestion("q3", List.of("a", "b"), 1),
                new QuizQuestion("q4", List.of("a", "b"), 1)), 75, 2));
        lp.publish("ins", free.id());

        paid = lp.createCourse("ins", "Advanced Java", "Programming", 100_00, false);
        lp.addModule("ins", paid.id(), "M1");
        for (int i = 1; i <= 4; i++) {
            lp.addLesson("ins", paid.id(), 0, new Lesson.Article("p" + i, "Article " + i, 500));
        }
        lp.publish("ins", paid.id());
    }

    private void finishFree(String s) {
        lp.enroll(s, free.id(), null);
        lp.completeLesson(s, free.id(), "f1");
        lp.submitQuiz(s, free.id(), "fq", List.of(1, 0, 1, 1));
    }

    // ------------------------------------------------------------------ authoring

    @Nested
    class Authoring {

        @Test
        void draftOnlyChangesAndOwnership() {
            Course c = lp.createCourse("ins", "New", "Data", 0, false);
            assertThrows(LearningException.class, () -> lp.publish("ins", c.id()), "empty course");
            lp.addModule("ins", c.id(), "M");
            assertThrows(LearningException.class, () -> lp.addLesson("ins", c.id(), 3, new Lesson.Video("x", "x", 1)));
            assertThrows(LearningException.class, () -> lp.addLesson("s1", c.id(), 0, new Lesson.Video("x", "x", 1)));
            assertThrows(LearningException.class, () -> lp.addLesson("ins", c.id(), 0, new Lesson.Video("f1", "dup", 1)),
                    "lesson ids are unique");
            lp.addLesson("ins", c.id(), 0, new Lesson.Video("x", "x", 1));
            assertThrows(LearningException.class, () -> lp.enroll("s1", c.id(), null), "still a draft");
            lp.publish("ins", c.id());
            assertThrows(LearningException.class, () -> lp.addLesson("ins", c.id(), 0, new Lesson.Video("y", "y", 1)));
            lp.archive("ins", c.id());
            assertThrows(LearningException.class, () -> lp.enroll("s1", c.id(), null));
        }

        @Test
        void prerequisiteCyclesAreRefused() {
            Course a = lp.createCourse("ins", "A", "X", 0, false);
            Course b = lp.createCourse("ins", "B", "X", 0, false);
            Course c = lp.createCourse("ins", "C", "X", 0, false);
            lp.addPrerequisite("ins", b.id(), a.id());
            lp.addPrerequisite("ins", c.id(), b.id());
            assertThrows(LearningException.class, () -> lp.addPrerequisite("ins", a.id(), c.id()), "a -> c -> b -> a");
            assertThrows(LearningException.class, () -> lp.addPrerequisite("ins", a.id(), a.id()));
        }

        @Test
        void quizValidation() {
            assertThrows(IllegalArgumentException.class, () -> new QuizQuestion("q", List.of("a"), 1));
            assertThrows(IllegalArgumentException.class, () -> new Lesson.Quiz("q", "q", List.of(), 50, 1));
        }
    }

    // ------------------------------------------------------------------ enrollment

    @Nested
    class Enrolling {

        @Test
        void paidEnrollmentChargesAndPrerequisitesAreChecked() {
            Course adv = lp.createCourse("ins", "Expert", "Programming", 50_00, false);
            lp.addModule("ins", adv.id(), "M");
            lp.addLesson("ins", adv.id(), 0, new Lesson.Video("e1", "e", 5));
            lp.addPrerequisite("ins", adv.id(), free.id());
            lp.publish("ins", adv.id());
            assertThrows(LearningException.class, () -> lp.enroll("s1", adv.id(), null));
            finishFree("s1");
            assertEquals(50_00, lp.enroll("s1", adv.id(), null).paidCents());
            assertEquals(50_00, payments.netCollected());
            assertThrows(LearningException.class, () -> lp.enroll("s1", adv.id(), null), "already enrolled");
            assertThrows(LearningException.class, () -> lp.enroll("ins", free.id(), null), "own course");
        }

        @Test
        void declinedPaymentMeansNoEnrollment() {
            payments.decline("s2");
            assertThrows(LearningException.class, () -> lp.enroll("s2", paid.id(), null));
            assertThrows(LearningException.class, () -> lp.enrollment("s2", paid.id()));
        }

        @Test
        void couponsDiscountExpireAndRunOut() {
            lp.createCoupon("ins", paid.id(), "HALF", 50, Duration.ofDays(1), 2);
            lp.createCoupon("ins", free.id(), "OTHER", 10, Duration.ofDays(1), 5);
            assertThrows(LearningException.class, () -> lp.enroll("s1", paid.id(), "OTHER"), "wrong course");
            assertThrows(LearningException.class, () -> lp.enroll("s1", paid.id(), "NOPE"));
            assertEquals(50_00, lp.enroll("s1", paid.id(), "HALF").paidCents());
            payments.decline("s2");
            assertThrows(LearningException.class, () -> lp.enroll("s2", paid.id(), "HALF"));
            assertEquals(50_00, lp.enroll("s3", paid.id(), "HALF").paidCents(), "a declined payment didn't use the coupon");
            lp.register("s4", "s4");
            assertThrows(LearningException.class, () -> lp.enroll("s4", paid.id(), "HALF"), "used up");
            lp.createCoupon("ins", paid.id(), "SOON", 20, Duration.ofHours(1), 5);
            clock.advance(Duration.ofHours(1));
            assertThrows(LearningException.class, () -> lp.enroll("s4", paid.id(), "SOON"), "expired");
        }

        @Test
        void hundredPercentCouponSkipsPayment() {
            lp.createCoupon("ins", paid.id(), "FREE", 100, Duration.ofDays(1), 1);
            Enrollment e = lp.enroll("s1", paid.id(), "FREE");
            assertEquals(0, e.paidCents());
            assertEquals(List.of(), payments.ledger());
        }
    }

    // ------------------------------------------------------------------ learning

    @Nested
    class Learning {

        @Test
        void sequentialCoursesLockLaterLessons() {
            lp.enroll("s1", free.id(), null);
            assertThrows(LearningException.class, () -> lp.submitQuiz("s1", free.id(), "fq", List.of(1, 0, 1, 1)));
            lp.completeLesson("s1", free.id(), "f1");
            assertEquals(50, lp.progress("s1", free.id()));
            assertEquals("fq", lp.nextLesson("s1", free.id()).orElseThrow().id());
        }

        @Test
        void nonSequentialCoursesAnyOrder() {
            lp.enroll("s1", paid.id(), null);
            lp.completeLesson("s1", paid.id(), "p4");
            lp.completeLesson("s1", paid.id(), "p2");
            assertEquals(50, lp.progress("s1", paid.id()));
            assertEquals("p1", lp.nextLesson("s1", paid.id()).orElseThrow().id());
        }

        @Test
        void quizScoringAttemptsAndPassMark() {
            lp.enroll("s1", free.id(), null);
            lp.completeLesson("s1", free.id(), "f1");
            assertThrows(LearningException.class, () -> lp.completeLesson("s1", free.id(), "fq"), "must pass");
            assertThrows(LearningException.class, () -> lp.submitQuiz("s1", free.id(), "fq", List.of(1)));
            LearningPlatform.QuizResult r1 = lp.submitQuiz("s1", free.id(), "fq", List.of(1, 0, 0, 0));
            assertEquals(new LearningPlatform.QuizResult(50, false, 1), r1);
            LearningPlatform.QuizResult r2 = lp.submitQuiz("s1", free.id(), "fq", List.of(1, 0, 1, 0));
            assertEquals(new LearningPlatform.QuizResult(75, true, 0), r2, "75% meets a 75% pass mark");
            assertThrows(LearningException.class, () -> lp.submitQuiz("s1", free.id(), "fq", List.of(1, 0, 1, 1)));
            assertEquals(List.of(50, 75), lp.enrollment("s1", free.id()).quizScores("fq"));
        }

        @Test
        void outOfAttemptsBlocksCompletion() {
            lp.enroll("s1", free.id(), null);
            lp.completeLesson("s1", free.id(), "f1");
            lp.submitQuiz("s1", free.id(), "fq", List.of(0, 0, 0, 0));
            lp.submitQuiz("s1", free.id(), "fq", List.of(0, 0, 0, 0));
            assertThrows(LearningException.class, () -> lp.submitQuiz("s1", free.id(), "fq", List.of(1, 0, 1, 1)));
            assertEquals(Enrollment.Status.ACTIVE, lp.enrollment("s1", free.id()).status());
        }

        @Test
        void completionIssuesAVerifiableCertificateOnce() {
            finishFree("s1");
            Enrollment e = lp.enrollment("s1", free.id());
            assertEquals(Enrollment.Status.COMPLETED, e.status());
            assertEquals(1, issued.size());
            Certificate c = e.certificate();
            assertEquals(12, c.verificationCode().length());
            assertEquals(c, lp.verify(c.verificationCode()).orElseThrow());
            assertTrue(lp.verify("000000000000").isEmpty());
            finishFree("s2");
            assertNotEquals(c.verificationCode(), lp.enrollment("s2", free.id()).certificate().verificationCode());
            assertThrows(LearningException.class, () -> lp.completeLesson("s1", free.id(), "zz"));
        }
    }

    // ------------------------------------------------------------------ refunds & reviews

    @Nested
    class RefundsAndReviews {

        @Test
        void refundRules() {
            lp.enroll("s1", paid.id(), null);
            lp.completeLesson("s1", paid.id(), "p1");                 // 25%
            clock.advance(Duration.ofDays(14));
            assertEquals(100_00, lp.refund("s1", paid.id()), "day 14 is still inside the window");
            assertEquals(0, payments.netCollected());
            assertThrows(LearningException.class, () -> lp.completeLesson("s1", paid.id(), "p2"), "no access after refund");
            assertThrows(LearningException.class, () -> lp.refund("s1", paid.id()));
            assertEquals(100_00, lp.enroll("s1", paid.id(), null).paidCents(), "can buy again");

            lp.enroll("s2", paid.id(), null);
            clock.advance(Duration.ofDays(15));
            assertThrows(LearningException.class, () -> lp.refund("s2", paid.id()), "too late");
            lp.enroll("s3", paid.id(), null);
            lp.completeLesson("s3", paid.id(), "p1");
            lp.completeLesson("s3", paid.id(), "p2");
            assertThrows(LearningException.class, () -> lp.refund("s3", paid.id()), "50% consumed");
        }

        @Test
        void reviewsNeedProgressOneEachAndLeaveWithARefund() {
            lp.enroll("s1", paid.id(), null);
            assertThrows(LearningException.class, () -> lp.review("s1", paid.id(), 5, "great"), "nothing watched yet");
            lp.completeLesson("s1", paid.id(), "p1");
            lp.review("s1", paid.id(), 2, "meh");
            lp.review("s1", paid.id(), 4, "grew on me");                 // replaces
            lp.enroll("s2", paid.id(), null);
            lp.completeLesson("s2", paid.id(), "p1");
            lp.review("s2", paid.id(), 5, "great");
            assertEquals(4.5, lp.averageRating(paid.id()));
            lp.refund("s2", paid.id());
            assertEquals(4.0, lp.averageRating(paid.id()));
            assertThrows(LearningException.class, () -> lp.review("s1", paid.id(), 6, "x"));
        }
    }

    // ------------------------------------------------------------------ catalog & stats

    @Test
    void catalogAndInstructorStats() {
        clock.advance(Duration.ofMinutes(1));
        Course newest = lp.createCourse("ins", "Java Performance", "Programming", 0, false);
        lp.addModule("ins", newest.id(), "M");
        lp.addLesson("ins", newest.id(), 0, new Lesson.Video("n1", "n", 3));
        lp.publish("ins", newest.id());
        finishFree("s1");
        finishFree("s2");
        lp.enroll("s3", paid.id(), null);
        lp.completeLesson("s3", paid.id(), "p1");
        lp.review("s3", paid.id(), 4, "ok");
        assertEquals(List.of(free, paid, newest), lp.search("java", null, LearningPlatform.Sort.POPULARITY));
        assertEquals(newest, lp.search("", "programming", LearningPlatform.Sort.NEWEST).get(0));
        assertEquals(paid, lp.search("java", null, LearningPlatform.Sort.RATING).get(0));
        assertEquals(List.of(), lp.search("python", null, LearningPlatform.Sort.RATING));
        LearningPlatform.CourseStats s = lp.instructorStats("ins").get(1);
        assertEquals(new LearningPlatform.CourseStats(paid.id(), "Advanced Java", 1, 0, 100_00, 4.0, 1), s);
        assertEquals(2, lp.instructorStats("ins").get(0).completions());
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void parallelEnrollmentsNeverExceedCouponUses() throws Exception {
        lp.createCoupon("ins", paid.id(), "FLASH", 30, Duration.ofDays(1), 10);
        List<String> students = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            lp.register("x" + i, "x" + i);
            students.add("x" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (String s : students) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    lp.enroll(s, paid.id(), "FLASH");
                    return true;
                } catch (LearningException usedUp) {
                    return false;
                }
            }));
        }
        start.countDown();
        int enrolled = 0;
        for (Future<Boolean> f : results) {
            if (f.get(10, TimeUnit.SECONDS)) {
                enrolled++;
            }
        }
        pool.shutdown();
        assertEquals(10, enrolled);
        assertEquals(10 * 70_00, payments.netCollected());
        assertFalse(lp.instructorStats("ins").isEmpty());
    }
}
