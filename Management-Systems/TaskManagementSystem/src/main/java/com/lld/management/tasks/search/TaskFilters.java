package com.lld.management.tasks.search;

import com.lld.management.tasks.core.Task;
import com.lld.management.tasks.model.Priority;
import com.lld.management.tasks.model.TaskStatus;
import com.lld.management.tasks.model.User;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Small, composable filters (Specification pattern). Screens combine them instead of the service
 * growing one search method per screen:
 * <pre>
 * assignedTo(asha).and(open()).and(priorityAtLeast(HIGH))
 * taggedWith("bug").and(overdue(today).or(dueBy(today)))
 * </pre>
 */
public final class TaskFilters {

    private TaskFilters() {
    }

    public static Predicate<Task> all() {
        return t -> true;
    }

    public static Predicate<Task> assignedTo(User user) {
        return t -> Objects.equals(t.assignee(), user);
    }

    public static Predicate<Task> unassigned() {
        return t -> t.assignee() == null;
    }

    public static Predicate<Task> reportedBy(User user) {
        return t -> t.reporter().equals(user);
    }

    public static Predicate<Task> inStatus(TaskStatus first, TaskStatus... more) {
        EnumSet<TaskStatus> wanted = EnumSet.of(first, more);
        return t -> wanted.contains(t.status());
    }

    public static Predicate<Task> open() {
        return t -> !t.status().isClosed();
    }

    public static Predicate<Task> priorityAtLeast(Priority minimum) {
        return t -> t.priority().compareTo(minimum) >= 0;
    }

    public static Predicate<Task> taggedWith(String tag) {
        String wanted = tag.toLowerCase(Locale.ROOT);
        return t -> t.tags().contains(wanted);
    }

    /** Due on or before the date (tasks without a due date never match). */
    public static Predicate<Task> dueBy(LocalDate date) {
        return t -> t.dueDate() != null && !t.dueDate().isAfter(date);
    }

    public static Predicate<Task> overdue(LocalDate today) {
        return t -> t.isOverdue(today);
    }

    public static Predicate<Task> topLevel() {
        return t -> t.parentId() == null;
    }

    /** Case-insensitive match in title or description. */
    public static Predicate<Task> text(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return t -> t.title().toLowerCase(Locale.ROOT).contains(q)
                || t.description().toLowerCase(Locale.ROOT).contains(q);
    }
}
