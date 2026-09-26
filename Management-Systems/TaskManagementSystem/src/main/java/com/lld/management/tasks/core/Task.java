package com.lld.management.tasks.core;

import com.lld.management.tasks.model.Activity;
import com.lld.management.tasks.model.Comment;
import com.lld.management.tasks.model.Priority;
import com.lld.management.tasks.model.TaskStatus;
import com.lld.management.tasks.model.User;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A unit of work. Anyone may read it; only {@link TaskService} (same package) may change it, after
 * checking permissions and workflow rules. Every change is written to the history and bumps the
 * {@link #version()}, which is what optimistic locking compares against.
 *
 * <p>Thread safety: every method is {@code synchronized} on the task, and the service holds the same
 * monitor while it checks-then-changes, so a check can never be invalidated halfway through.
 */
public final class Task {

    private final String id;
    private final long number;
    private final User reporter;
    private final Instant createdAt;
    private final String parentId;

    private String title;
    private String description;
    private Priority priority;
    private TaskStatus status = TaskStatus.TODO;
    private User assignee;
    private LocalDate dueDate;
    private final Set<String> tags;
    private final List<String> subtaskIds = new ArrayList<>();
    private final List<Comment> comments = new ArrayList<>();
    private final List<Activity> history = new ArrayList<>();
    private long version = 1;
    private Instant updatedAt;

    Task(long number, NewTask req, Instant now) {
        this.number = number;
        this.id = "TASK-" + number;
        this.reporter = req.reporter;
        this.createdAt = now;
        this.updatedAt = now;
        this.parentId = req.parentId;
        this.title = req.title;
        this.description = req.description;
        this.priority = req.priority;
        this.assignee = req.assignee;
        this.dueDate = req.dueDate;
        this.tags = new LinkedHashSet<>(req.tags);
        history.add(new Activity(now, req.reporter, "created"));
    }

    // ------------------------------------------------------------------ reads

    public String id() {
        return id;
    }

    /** Creation order; handy as a stable tie-breaker when sorting. */
    public long number() {
        return number;
    }

    public User reporter() {
        return reporter;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String parentId() {
        return parentId;
    }

    public synchronized String title() {
        return title;
    }

    public synchronized String description() {
        return description;
    }

    public synchronized Priority priority() {
        return priority;
    }

    public synchronized TaskStatus status() {
        return status;
    }

    public synchronized User assignee() {
        return assignee;
    }

    public synchronized LocalDate dueDate() {
        return dueDate;
    }

    public synchronized Set<String> tags() {
        return Set.copyOf(tags);
    }

    public synchronized List<String> subtaskIds() {
        return List.copyOf(subtaskIds);
    }

    public synchronized List<Comment> comments() {
        return List.copyOf(comments);
    }

    public synchronized List<Activity> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public synchronized long version() {
        return version;
    }

    public synchronized Instant updatedAt() {
        return updatedAt;
    }

    public synchronized boolean isOverdue(LocalDate today) {
        return dueDate != null && !status.isClosed() && dueDate.isBefore(today);
    }

    /** May this user edit / move / reassign the task? */
    public synchronized boolean canBeChangedBy(User user) {
        return user.isManager() || user.equals(reporter) || user.equals(assignee);
    }

    // ------------------------------------------------------------------ changes (service only)

    synchronized void setStatus(TaskStatus next, User actor, Instant at) {
        record(at, actor, "status " + status + " -> " + next);
        status = next;
    }

    synchronized void setAssignee(User next, User actor, Instant at) {
        record(at, actor, "assignee " + (assignee == null ? "nobody" : assignee.name()) + " -> "
                + (next == null ? "nobody" : next.name()));
        assignee = next;
    }

    synchronized void setTitle(String next, User actor, Instant at) {
        record(at, actor, "title '" + title + "' -> '" + next + "'");
        title = next;
    }

    synchronized void setDescription(String next, User actor, Instant at) {
        record(at, actor, "description edited");
        description = next;
    }

    synchronized void setPriority(Priority next, User actor, Instant at) {
        record(at, actor, "priority " + priority + " -> " + next);
        priority = next;
    }

    synchronized void setDueDate(LocalDate next, User actor, Instant at) {
        record(at, actor, "due " + (dueDate == null ? "none" : dueDate) + " -> " + (next == null ? "none" : next));
        dueDate = next;
    }

    synchronized void addTag(String tag, User actor, Instant at) {
        if (tags.add(tag)) {
            record(at, actor, "tag +" + tag);
        }
    }

    synchronized void removeTag(String tag, User actor, Instant at) {
        if (tags.remove(tag)) {
            record(at, actor, "tag -" + tag);
        }
    }

    synchronized void addSubtask(String subtaskId, User actor, Instant at) {
        subtaskIds.add(subtaskId);
        record(at, actor, "subtask " + subtaskId + " added");
    }

    /** Comments are conversation, not edits: they don't bump the version, so they never make a form stale. */
    synchronized void addComment(Comment comment) {
        comments.add(comment);
    }

    private void record(Instant at, User actor, String change) {
        history.add(new Activity(at, actor, change));
        version++;
        updatedAt = at;
    }

    @Override
    public synchronized String toString() {
        return String.format("%-8s %-9s %-11s %-28s %s%s", id, priority, status, title,
                assignee == null ? "(unassigned)" : "@" + assignee.name(),
                dueDate == null ? "" : "  due " + dueDate);
    }
}
