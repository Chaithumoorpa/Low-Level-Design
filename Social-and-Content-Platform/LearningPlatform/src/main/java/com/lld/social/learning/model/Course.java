package com.lld.social.learning.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A course: modules of lessons, owned by one instructor.
 * <pre>
 * DRAFT --publish (needs content)--> PUBLISHED --archive--> ARCHIVED
 * </pre>
 * Content can only change while DRAFT, so enrolled students never see lessons shift under them.
 * Archived courses take no new students; existing students keep access.
 */
public final class Course {

    public enum Status {
        DRAFT, PUBLISHED, ARCHIVED
    }

    public record Module(String title, List<Lesson> lessons) {
    }

    private final String id;
    private final String instructorId;
    private final String title;
    private final String category;
    private final long priceCents;
    private final boolean sequential;
    private final Instant createdAt;
    private final List<Module> modules = new ArrayList<>();
    private final Set<String> prerequisites = new LinkedHashSet<>();
    private Status status = Status.DRAFT;

    public Course(String id, String instructorId, String title, String category, long priceCents, boolean sequential,
                  Instant createdAt) {
        this.id = id;
        this.instructorId = instructorId;
        this.title = title;
        this.category = category;
        this.priceCents = priceCents;
        this.sequential = sequential;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String instructorId() {
        return instructorId;
    }

    public String title() {
        return title;
    }

    public String category() {
        return category;
    }

    public long priceCents() {
        return priceCents;
    }

    /** Lessons unlock one after another (each needs the previous one completed). */
    public boolean sequential() {
        return sequential;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public List<Module> modules() {
        return modules.stream().map(m -> new Module(m.title(), List.copyOf(m.lessons()))).toList();
    }

    public void addModule(String title) {
        modules.add(new Module(title, new ArrayList<>()));
    }

    public void addLesson(int moduleIndex, Lesson lesson) {
        modules.get(moduleIndex).lessons().add(lesson);
    }

    public int moduleCount() {
        return modules.size();
    }

    /** All lessons in course order. */
    public List<Lesson> lessons() {
        List<Lesson> out = new ArrayList<>();
        modules.forEach(m -> out.addAll(m.lessons()));
        return out;
    }

    public Optional<Lesson> lesson(String lessonId) {
        return lessons().stream().filter(l -> l.id().equals(lessonId)).findFirst();
    }

    public int totalMinutes() {
        return lessons().stream().mapToInt(l -> l instanceof Lesson.Video v ? v.minutes() : 0).sum();
    }

    public Set<String> prerequisites() {
        return Set.copyOf(prerequisites);
    }

    public void addPrerequisite(String courseId) {
        prerequisites.add(courseId);
    }

    @Override
    public String toString() {
        return id + " '" + title + "' (" + category + ", " + String.format("$%d.%02d", priceCents / 100, priceCents % 100)
                + ", " + lessons().size() + " lessons) [" + status + "]";
    }
}
