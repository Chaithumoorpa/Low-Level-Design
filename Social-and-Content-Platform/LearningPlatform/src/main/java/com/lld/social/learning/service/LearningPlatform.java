package com.lld.social.learning.service;

import com.lld.social.learning.model.Certificate;
import com.lld.social.learning.model.Coupon;
import com.lld.social.learning.model.Course;
import com.lld.social.learning.model.Enrollment;
import com.lld.social.learning.model.LearningException;
import com.lld.social.learning.model.Lesson;
import com.lld.social.learning.model.Review;
import com.lld.social.learning.model.User;
import com.lld.social.learning.payment.PaymentPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Facade for instructors (build, publish, see stats) and students (enroll, learn, get certified,
 * refund, review). One lock for all state; payment calls go through a {@link PaymentPort}.
 */
public final class LearningPlatform {

    public enum Sort {
        RATING, POPULARITY, NEWEST
    }

    public record QuizResult(int percent, boolean passed, int attemptsLeft) {
    }

    public record CourseStats(String courseId, String title, int enrollments, int completions, long revenueCents,
                              double averageRating, int reviews) {
    }

    public static final Duration REFUND_WINDOW = Duration.ofDays(14);
    public static final int REFUND_MAX_PROGRESS = 30;

    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Course> courses = new LinkedHashMap<>();
    private final Map<String, Enrollment> enrollments = new LinkedHashMap<>();       // "student|course"
    private final Map<String, Coupon> coupons = new HashMap<>();
    private final Map<String, Map<String, Review>> reviews = new HashMap<>();       // course -> student -> review
    private final Map<String, Certificate> certificatesByCode = new HashMap<>();
    private final List<Consumer<Certificate>> completionListeners = new CopyOnWriteArrayList<>();
    private final PaymentPort payments;
    private final Clock clock;
    private final String secret;
    private long courseSeq;
    private long certificateSeq;

    public LearningPlatform(PaymentPort payments, Clock clock, String certificateSecret) {
        this.payments = Objects.requireNonNull(payments);
        this.clock = Objects.requireNonNull(clock);
        this.secret = Objects.requireNonNull(certificateSecret);
    }

    /** Observer for "course completed" (e-mail the certificate, update a profile...). */
    public void onCompletion(Consumer<Certificate> listener) {
        completionListeners.add(listener);
    }

    public synchronized User register(String id, String name) {
        if (users.containsKey(id)) {
            throw new LearningException("User " + id + " exists");
        }
        User u = new User(id, name);
        users.put(id, u);
        return u;
    }

    // ------------------------------------------------------------------ authoring (instructors)

    public synchronized Course createCourse(String instructorId, String title, String category, long priceCents,
                                            boolean sequential) {
        user(instructorId);
        if (priceCents < 0) {
            throw new LearningException("Negative price");
        }
        Course c = new Course("C" + (++courseSeq), instructorId, title, category, priceCents, sequential, now());
        courses.put(c.id(), c);
        return c;
    }

    public synchronized void addModule(String instructorId, String courseId, String title) {
        draftOwnedBy(instructorId, courseId).addModule(title);
    }

    public synchronized void addLesson(String instructorId, String courseId, int moduleIndex, Lesson lesson) {
        Course c = draftOwnedBy(instructorId, courseId);
        if (moduleIndex < 0 || moduleIndex >= c.moduleCount()) {
            throw new LearningException("No module " + moduleIndex + " in " + courseId);
        }
        boolean duplicate = courses.values().stream().anyMatch(x -> x.lesson(lesson.id()).isPresent());
        if (duplicate) {
            throw new LearningException("Lesson id " + lesson.id() + " is already used");
        }
        c.addLesson(moduleIndex, lesson);
    }

    public synchronized void addPrerequisite(String instructorId, String courseId, String prerequisiteId) {
        Course c = draftOwnedBy(instructorId, courseId);
        course(prerequisiteId);
        if (prerequisiteId.equals(courseId) || requires(prerequisiteId, courseId)) {
            throw new LearningException("Prerequisites can't form a cycle");
        }
        c.addPrerequisite(prerequisiteId);
    }

    public synchronized void publish(String instructorId, String courseId) {
        Course c = draftOwnedBy(instructorId, courseId);
        if (c.lessons().isEmpty()) {
            throw new LearningException("Add at least one lesson before publishing");
        }
        c.setStatus(Course.Status.PUBLISHED);
    }

    /** No new enrollments; enrolled students keep their access. */
    public synchronized void archive(String instructorId, String courseId) {
        Course c = ownedBy(instructorId, courseId);
        if (c.status() != Course.Status.PUBLISHED) {
            throw new LearningException("Only published courses can be archived");
        }
        c.setStatus(Course.Status.ARCHIVED);
    }

    public synchronized Coupon createCoupon(String instructorId, String courseId, String code, int percentOff,
                                            Duration validFor, int maxUses) {
        ownedBy(instructorId, courseId);
        if (coupons.containsKey(code)) {
            throw new LearningException("Coupon " + code + " exists");
        }
        Coupon coupon = new Coupon(code, courseId, percentOff, now().plus(validFor), maxUses);
        coupons.put(code, coupon);
        return coupon;
    }

    // ------------------------------------------------------------------ enrolling

    public synchronized Enrollment enroll(String studentId, String courseId, String couponCode) {
        user(studentId);
        Course c = course(courseId);
        if (c.status() != Course.Status.PUBLISHED) {
            throw new LearningException(c.title() + " is not open for enrollment");
        }
        if (c.instructorId().equals(studentId)) {
            throw new LearningException("Instructors can't enroll in their own course");
        }
        Enrollment existing = enrollments.get(key(studentId, courseId));
        if (existing != null && existing.hasAccess()) {
            throw new LearningException("Already enrolled");
        }
        List<String> missing = c.prerequisites().stream().filter(p -> !completed(studentId, p))
                .map(p -> course(p).title()).toList();
        if (!missing.isEmpty()) {
            throw new LearningException("Complete " + missing + " first");
        }
        long price = c.priceCents();
        Coupon coupon = null;
        if (couponCode != null) {
            coupon = coupons.get(couponCode);
            String problem = coupon == null ? "unknown coupon " + couponCode : coupon.problem(courseId, now());
            if (problem != null) {
                throw new LearningException(problem);
            }
            price = coupon.apply(price);
        }
        String reference = null;
        if (price > 0) {
            try {
                reference = payments.charge(studentId, price, c.title());
            } catch (PaymentPort.PaymentDeclined e) {
                throw new LearningException("Payment failed: " + e.getMessage());
            }
        }
        if (coupon != null) {
            coupon.use();
        }
        Enrollment e = new Enrollment(studentId, courseId, now(), price, reference);
        enrollments.put(key(studentId, courseId), e);
        return e;
    }

    // ------------------------------------------------------------------ learning

    public synchronized void completeLesson(String studentId, String courseId, String lessonId) {
        Enrollment e = activeEnrollment(studentId, courseId);
        Course c = course(courseId);
        Lesson lesson = c.lesson(lessonId).orElseThrow(() -> new LearningException("No lesson " + lessonId));
        if (lesson instanceof Lesson.Quiz) {
            throw new LearningException("Quizzes are completed by passing them");
        }
        requireUnlocked(c, e, lessonId);
        e.complete(lessonId);
        checkCompletion(c, e);
    }

    /** Scores the answers; passing completes the quiz lesson. Failing uses up an attempt. */
    public synchronized QuizResult submitQuiz(String studentId, String courseId, String lessonId, List<Integer> answers) {
        Enrollment e = activeEnrollment(studentId, courseId);
        Course c = course(courseId);
        if (!(c.lesson(lessonId).orElse(null) instanceof Lesson.Quiz quiz)) {
            throw new LearningException(lessonId + " is not a quiz in " + courseId);
        }
        requireUnlocked(c, e, lessonId);
        if (e.isCompleted(lessonId)) {
            throw new LearningException("Quiz already passed");
        }
        int used = e.quizScores(lessonId).size();
        if (used >= quiz.maxAttempts()) {
            throw new LearningException("No attempts left for " + quiz.title());
        }
        if (answers.size() != quiz.questions().size()) {
            throw new LearningException("Answer all " + quiz.questions().size() + " questions");
        }
        int correct = 0;
        for (int i = 0; i < answers.size(); i++) {
            if (answers.get(i) == quiz.questions().get(i).correctIndex()) {
                correct++;
            }
        }
        int percent = correct * 100 / answers.size();
        boolean passed = percent >= quiz.passPercent();
        e.recordQuiz(lessonId, percent);
        if (passed) {
            e.complete(lessonId);
            checkCompletion(c, e);
        }
        return new QuizResult(percent, passed, quiz.maxAttempts() - used - 1);
    }

    /** Share of lessons completed, 0-100. */
    public synchronized int progress(String studentId, String courseId) {
        Enrollment e = enrollment(studentId, courseId);
        int total = course(courseId).lessons().size();
        return total == 0 ? 0 : e.completedLessons().size() * 100 / total;
    }

    /** Next lesson to take (first not completed), if any. */
    public synchronized Optional<Lesson> nextLesson(String studentId, String courseId) {
        Enrollment e = enrollment(studentId, courseId);
        return course(courseId).lessons().stream().filter(l -> !e.isCompleted(l.id())).findFirst();
    }

    private void requireUnlocked(Course c, Enrollment e, String lessonId) {
        if (!c.sequential()) {
            return;
        }
        for (Lesson l : c.lessons()) {
            if (l.id().equals(lessonId)) {
                return;
            }
            if (!e.isCompleted(l.id())) {
                throw new LearningException(lessonId + " is locked: finish '" + l.title() + "' first");
            }
        }
    }

    private void checkCompletion(Course c, Enrollment e) {
        if (e.status() == Enrollment.Status.ACTIVE && e.completedLessons().size() == c.lessons().size()) {
            String n = String.format("%05d", ++certificateSeq);
            Certificate cert = new Certificate("CERT-" + n, e.studentId(), c.id(), c.title(), now(),
                    verificationCode("CERT-" + n, e.studentId(), c.id()));
            e.setCertificate(cert);
            e.setStatus(Enrollment.Status.COMPLETED);
            certificatesByCode.put(cert.verificationCode(), cert);
            completionListeners.forEach(l -> l.accept(cert));
        }
    }

    /**
     * Not guessable from the certificate number: the first 12 hex digits of SHA-256 over the certificate
     * data and a platform secret (in production: an HMAC key from a secrets store).
     */
    private String verificationCode(String certId, String studentId, String courseId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((secret + "|" + certId + "|" + studentId + "|" + courseId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized Optional<Certificate> verify(String code) {
        return Optional.ofNullable(certificatesByCode.get(code));
    }

    // ------------------------------------------------------------------ refunds & reviews

    /** Within 14 days, under 30% progress, not completed. Access is removed. */
    public synchronized long refund(String studentId, String courseId) {
        Enrollment e = activeEnrollment(studentId, courseId);
        if (now().isAfter(e.enrolledAt().plus(REFUND_WINDOW))) {
            throw new LearningException("Refunds are possible within " + REFUND_WINDOW.toDays() + " days");
        }
        int p = progress(studentId, courseId);
        if (p >= REFUND_MAX_PROGRESS) {
            throw new LearningException("Too much of the course consumed for a refund (" + p + "%)");
        }
        if (e.paymentReference() != null) {
            payments.refund(e.paymentReference(), e.paidCents());
        }
        e.setStatus(Enrollment.Status.REFUNDED);
        reviews.getOrDefault(courseId, new HashMap<>()).remove(studentId);   // a refunded student's review is withdrawn
        return e.paidCents();
    }

    /** One review per student (writing again replaces it); needs at least one completed lesson. */
    public synchronized Review review(String studentId, String courseId, int stars, String text) {
        Enrollment e = enrollment(studentId, courseId);
        if (!e.hasAccess() || e.completedLessons().isEmpty()) {
            throw new LearningException("Only students who have started the course can review it");
        }
        Review r = new Review(studentId, courseId, stars, text, now());
        reviews.computeIfAbsent(courseId, k -> new LinkedHashMap<>()).put(studentId, r);
        return r;
    }

    public synchronized double averageRating(String courseId) {
        course(courseId);
        return reviews.getOrDefault(courseId, Map.of()).values().stream().mapToInt(Review::stars).average().orElse(0);
    }

    // ------------------------------------------------------------------ catalog & stats

    public synchronized List<Course> search(String query, String category, Sort sort) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Course> out = new ArrayList<>(courses.values().stream()
                .filter(c -> c.status() == Course.Status.PUBLISHED)
                .filter(c -> category == null || c.category().equalsIgnoreCase(category))
                .filter(c -> c.title().toLowerCase(Locale.ROOT).contains(q))
                .toList());
        Comparator<Course> order = switch (sort) {
            case RATING -> Comparator.comparingDouble((Course c) -> averageRating(c.id())).reversed();
            case POPULARITY -> Comparator.comparingInt((Course c) -> activeEnrollments(c.id())).reversed();
            case NEWEST -> Comparator.comparing(Course::createdAt).reversed();
        };
        out.sort(order.thenComparing(Course::id));
        return out;
    }

    public synchronized List<CourseStats> instructorStats(String instructorId) {
        user(instructorId);
        List<CourseStats> out = new ArrayList<>();
        for (Course c : courses.values()) {
            if (!c.instructorId().equals(instructorId)) {
                continue;
            }
            List<Enrollment> es = enrollments.values().stream().filter(e -> e.courseId().equals(c.id())).toList();
            long revenue = es.stream().filter(Enrollment::hasAccess).mapToLong(Enrollment::paidCents).sum();
            int completions = (int) es.stream().filter(e -> e.status() == Enrollment.Status.COMPLETED).count();
            out.add(new CourseStats(c.id(), c.title(), (int) es.stream().filter(Enrollment::hasAccess).count(),
                    completions, revenue, averageRating(c.id()), reviews.getOrDefault(c.id(), Map.of()).size()));
        }
        return out;
    }

    public synchronized Enrollment enrollment(String studentId, String courseId) {
        Enrollment e = enrollments.get(key(studentId, courseId));
        if (e == null) {
            throw new LearningException(studentId + " is not enrolled in " + courseId);
        }
        return e;
    }

    public synchronized Course course(String id) {
        Course c = courses.get(id);
        if (c == null) {
            throw new LearningException("No course " + id);
        }
        return c;
    }

    // ------------------------------------------------------------------ internals

    private int activeEnrollments(String courseId) {
        return (int) enrollments.values().stream().filter(e -> e.courseId().equals(courseId) && e.hasAccess()).count();
    }

    private boolean completed(String studentId, String courseId) {
        Enrollment e = enrollments.get(key(studentId, courseId));
        return e != null && e.status() == Enrollment.Status.COMPLETED;
    }

    /** Does {@code courseId} (transitively) require {@code target}? */
    private boolean requires(String courseId, String target) {
        for (String p : course(courseId).prerequisites()) {
            if (p.equals(target) || requires(p, target)) {
                return true;
            }
        }
        return false;
    }

    private Enrollment activeEnrollment(String studentId, String courseId) {
        Enrollment e = enrollment(studentId, courseId);
        if (!e.hasAccess()) {
            throw new LearningException("Enrollment in " + courseId + " was refunded");
        }
        return e;
    }

    private Course ownedBy(String instructorId, String courseId) {
        Course c = course(courseId);
        if (!c.instructorId().equals(instructorId)) {
            throw new LearningException(instructorId + " does not own " + courseId);
        }
        return c;
    }

    private Course draftOwnedBy(String instructorId, String courseId) {
        Course c = ownedBy(instructorId, courseId);
        if (c.status() != Course.Status.DRAFT) {
            throw new LearningException(courseId + " is " + c.status() + "; content is frozen after publishing");
        }
        return c;
    }

    private User user(String id) {
        User u = users.get(id);
        if (u == null) {
            throw new LearningException("No user " + id);
        }
        return u;
    }

    private static String key(String studentId, String courseId) {
        return studentId + "|" + courseId;
    }

    private Instant now() {
        return clock.instant();
    }
}
