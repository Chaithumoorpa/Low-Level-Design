package com.lld.social.learning.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A student's place in a course: what they paid, which lessons they finished, their quiz attempts.
 * ACTIVE → COMPLETED (all lessons done, certificate issued) or → REFUNDED (access removed).
 */
public final class Enrollment {

    public enum Status {
        ACTIVE, COMPLETED, REFUNDED
    }

    private final String studentId;
    private final String courseId;
    private final Instant enrolledAt;
    private final long paidCents;
    private final String paymentReference;
    private final Set<String> completedLessons = new LinkedHashSet<>();
    private final Map<String, List<Integer>> quizScores = new HashMap<>();
    private Status status = Status.ACTIVE;
    private Certificate certificate;

    public Enrollment(String studentId, String courseId, Instant enrolledAt, long paidCents, String paymentReference) {
        this.studentId = studentId;
        this.courseId = courseId;
        this.enrolledAt = enrolledAt;
        this.paidCents = paidCents;
        this.paymentReference = paymentReference;
    }

    public String studentId() {
        return studentId;
    }

    public String courseId() {
        return courseId;
    }

    public Instant enrolledAt() {
        return enrolledAt;
    }

    public long paidCents() {
        return paidCents;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean hasAccess() {
        return status != Status.REFUNDED;
    }

    public Set<String> completedLessons() {
        return Set.copyOf(completedLessons);
    }

    public boolean isCompleted(String lessonId) {
        return completedLessons.contains(lessonId);
    }

    public void complete(String lessonId) {
        completedLessons.add(lessonId);
    }

    public List<Integer> quizScores(String lessonId) {
        return List.copyOf(quizScores.getOrDefault(lessonId, List.of()));
    }

    public void recordQuiz(String lessonId, int percent) {
        quizScores.computeIfAbsent(lessonId, k -> new ArrayList<>()).add(percent);
    }

    public Certificate certificate() {
        return certificate;
    }

    public void setCertificate(Certificate certificate) {
        this.certificate = certificate;
    }
}
