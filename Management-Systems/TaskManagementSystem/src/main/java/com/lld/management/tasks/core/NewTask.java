package com.lld.management.tasks.core;

import com.lld.management.tasks.model.Priority;
import com.lld.management.tasks.model.User;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Builder for a task-creation request: only title and reporter are required, everything else has a
 * sensible default. Keeps {@code TaskService.create} from growing a ten-argument signature.
 */
public final class NewTask {

    final String title;
    final User reporter;
    final String description;
    final Priority priority;
    final User assignee;
    final LocalDate dueDate;
    final Set<String> tags;
    final String parentId;

    private NewTask(Builder b) {
        this.title = b.title;
        this.reporter = b.reporter;
        this.description = b.description;
        this.priority = b.priority;
        this.assignee = b.assignee;
        this.dueDate = b.dueDate;
        this.tags = new LinkedHashSet<>(b.tags);
        this.parentId = b.parentId;
    }

    public static Builder titled(String title) {
        return new Builder(title);
    }

    public static final class Builder {
        private final String title;
        private User reporter;
        private String description = "";
        private Priority priority = Priority.MEDIUM;
        private User assignee;
        private LocalDate dueDate;
        private final Set<String> tags = new LinkedHashSet<>();
        private String parentId;

        private Builder(String title) {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("A task needs a title");
            }
            this.title = title.strip();
        }

        public Builder by(User reporter) {
            this.reporter = reporter;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder priority(Priority priority) {
            this.priority = Objects.requireNonNull(priority);
            return this;
        }

        public Builder assignTo(User assignee) {
            this.assignee = assignee;
            return this;
        }

        public Builder due(LocalDate dueDate) {
            this.dueDate = dueDate;
            return this;
        }

        public Builder tag(String... tags) {
            for (String t : tags) {
                this.tags.add(t.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /** Makes this a subtask of an existing task. */
        public Builder under(String parentId) {
            this.parentId = parentId;
            return this;
        }

        public NewTask build() {
            if (reporter == null) {
                throw new IllegalArgumentException("A task needs a reporter: call by(user)");
            }
            return new NewTask(this);
        }
    }
}
