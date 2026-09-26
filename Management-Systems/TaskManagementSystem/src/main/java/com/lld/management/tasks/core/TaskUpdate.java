package com.lld.management.tasks.core;

import com.lld.management.tasks.model.Priority;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A partial edit from a form: only the fields that were set are changed. Sent together with the
 * version the user was looking at, so a stale form can't silently overwrite someone else's change.
 */
public final class TaskUpdate {

    String title;
    String description;
    Priority priority;
    LocalDate dueDate;
    boolean clearDueDate;
    final Set<String> addTags = new LinkedHashSet<>();
    final Set<String> removeTags = new LinkedHashSet<>();

    public static TaskUpdate change() {
        return new TaskUpdate();
    }

    public TaskUpdate title(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title can't be blank");
        }
        this.title = title.strip();
        return this;
    }

    public TaskUpdate description(String description) {
        this.description = description;
        return this;
    }

    public TaskUpdate priority(Priority priority) {
        this.priority = priority;
        return this;
    }

    public TaskUpdate due(LocalDate dueDate) {
        this.dueDate = dueDate;
        return this;
    }

    public TaskUpdate noDueDate() {
        this.clearDueDate = true;
        return this;
    }

    public TaskUpdate addTag(String tag) {
        addTags.add(tag.toLowerCase(Locale.ROOT));
        return this;
    }

    public TaskUpdate removeTag(String tag) {
        removeTags.add(tag.toLowerCase(Locale.ROOT));
        return this;
    }
}
