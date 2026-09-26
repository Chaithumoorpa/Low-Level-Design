package com.lld.social.learning;

import com.lld.social.learning.model.Course;
import com.lld.social.learning.model.LearningException;
import com.lld.social.learning.model.Lesson;
import com.lld.social.learning.model.Lesson.QuizQuestion;
import com.lld.social.learning.payment.FakePayments;
import com.lld.social.learning.service.LearningPlatform;
import com.lld.social.learning.service.ManualClock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** Two Java courses, one instructor, three students, on a simulated clock. */
public class LearningPlatformApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2027-08-01T09:00:00Z"));
        FakePayments payments = new FakePayments();
        LearningPlatform lp = new LearningPlatform(payments, clock, "demo-secret");
        lp.onCompletion(c -> System.out.println("   [certificate] " + c.id() + " for " + c.studentId()
                + " in '" + c.courseTitle() + "', verify with code " + c.verificationCode()));
        lp.register("ines", "Ines (instructor)");
        for (String s : List.of("sam", "tia", "uma")) {
            lp.register(s, s.substring(0, 1).toUpperCase() + s.substring(1));
        }

        step("Ines builds two courses");
        Course basics = lp.createCourse("ines", "Java Basics", "Programming", 0, true);
        lp.addModule("ines", basics.id(), "Getting started");
        lp.addLesson("ines", basics.id(), 0, new Lesson.Video("b1", "Install the JDK", 8));
        lp.addLesson("ines", basics.id(), 0, new Lesson.Article("b2", "Hello, World explained", 900));
        lp.addLesson("ines", basics.id(), 0, new Lesson.Quiz("b3", "Basics quiz", List.of(
                new QuizQuestion("Which keyword makes a constant?", List.of("static", "final", "const"), 1),
                new QuizQuestion("Entry point method?", List.of("main", "start", "run"), 0)), 100, 2));
        Course streams = lp.createCourse("ines", "Java Streams in Depth", "Programming", 49_99, true);
        lp.addModule("ines", streams.id(), "Pipelines");
        lp.addLesson("ines", streams.id(), 0, new Lesson.Video("s1", "map, filter, reduce", 14));
        lp.addLesson("ines", streams.id(), 0, new Lesson.Video("s2", "Collectors", 18));
        lp.addModule("ines", streams.id(), "Advanced");
        lp.addLesson("ines", streams.id(), 1, new Lesson.Video("s3", "Parallel streams", 12));
        lp.addLesson("ines", streams.id(), 1, new Lesson.Article("s4", "Pitfalls", 1500));
        lp.addPrerequisite("ines", streams.id(), basics.id());
        attempt(() -> {
            lp.addPrerequisite("ines", basics.id(), streams.id());
            return "ok";
        });
        lp.publish("ines", basics.id());
        lp.publish("ines", streams.id());
        attempt(() -> {
            lp.addLesson("ines", streams.id(), 1, new Lesson.Video("s5", "Late addition", 5));
            return "ok";
        });
        lp.createCoupon("ines", streams.id(), "LAUNCH40", 40, Duration.ofDays(7), 2);
        System.out.println("   " + basics + "\n   " + streams);

        step("Sam: prerequisites, locked lessons, a quiz");
        attempt(() -> lp.enroll("sam", streams.id(), null));
        lp.enroll("sam", basics.id(), null);
        attempt(() -> {
            lp.completeLesson("sam", basics.id(), "b2");
            return "ok";
        });
        lp.completeLesson("sam", basics.id(), "b1");
        lp.completeLesson("sam", basics.id(), "b2");
        System.out.println("   progress " + lp.progress("sam", basics.id()) + "%, next: " + lp.nextLesson("sam", basics.id()).orElseThrow().title());
        System.out.println("   attempt 1: " + lp.submitQuiz("sam", basics.id(), "b3", List.of(0, 0)));
        System.out.println("   attempt 2: " + lp.submitQuiz("sam", basics.id(), "b3", List.of(1, 0)));

        step("Sam enrolls in the paid course with a coupon");
        System.out.println("   paid " + money(lp.enroll("sam", streams.id(), "LAUNCH40").paidCents()) + " instead of " + money(streams.priceCents()));
        for (String l : List.of("s1", "s2", "s3", "s4")) {
            lp.completeLesson("sam", streams.id(), l);
        }
        lp.review("sam", streams.id(), 5, "Finally understood collectors");

        step("Tia and Uma");
        lp.enroll("tia", basics.id(), null);
        lp.completeLesson("tia", basics.id(), "b1");
        lp.completeLesson("tia", basics.id(), "b2");
        lp.submitQuiz("tia", basics.id(), "b3", List.of(1, 0));
        lp.enroll("tia", streams.id(), "LAUNCH40");
        lp.completeLesson("tia", streams.id(), "s1");
        lp.review("tia", streams.id(), 3, "Good but fast");
        clock.advance(Duration.ofDays(3));
        System.out.println("   Tia asks for a refund at " + lp.progress("tia", streams.id()) + "% progress: "
                + money(lp.refund("tia", streams.id())) + " back");
        lp.enroll("uma", basics.id(), null);
        attempt(() -> lp.submitQuiz("uma", basics.id(), "b3", List.of(1, 0)));
        lp.completeLesson("uma", basics.id(), "b1");
        lp.completeLesson("uma", basics.id(), "b2");
        lp.submitQuiz("uma", basics.id(), "b3", List.of(1, 0));
        attempt(() -> lp.enroll("uma", streams.id(), "LAUNCH40"));

        step("Catalog, verification and instructor dashboard");
        System.out.println("   'java' by popularity: " + lp.search("java", null, LearningPlatform.Sort.POPULARITY)
                .stream().map(Course::title).toList());
        String code = lp.enrollment("sam", streams.id()).certificate().verificationCode();
        System.out.println("   verify " + code + ": " + lp.verify(code).map(c -> c.studentId() + " completed " + c.courseTitle()).orElse("invalid"));
        System.out.println("   verify BOGUS: " + lp.verify("BOGUS").map(Object::toString).orElse("invalid"));
        lp.instructorStats("ines").forEach(s -> System.out.printf("   %s: %d students, %d completed, revenue %s, rating %.1f (%d reviews)%n",
                s.title(), s.enrollments(), s.completions(), money(s.revenueCents()), s.averageRating(), s.reviews()));
        System.out.println("   payments net: " + money(payments.netCollected()));
    }

    private static String money(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (LearningException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
