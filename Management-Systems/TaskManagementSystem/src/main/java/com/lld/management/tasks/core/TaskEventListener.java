package com.lld.management.tasks.core;

import com.lld.management.tasks.model.Comment;
import com.lld.management.tasks.model.TaskStatus;
import com.lld.management.tasks.model.User;

/**
 * Observer for everything that happens to tasks: notifications, e-mail, webhooks, analytics.
 * All methods are optional. Events are published after the change is committed and outside any lock.
 */
public interface TaskEventListener {

    default void onCreated(Task task) {
    }

    default void onAssigned(Task task, User from, User to, User actor) {
    }

    default void onStatusChanged(Task task, TaskStatus from, TaskStatus to, User actor) {
    }

    default void onCommented(Task task, Comment comment) {
    }

    default void onDueSoon(Task task) {
    }

    default void onOverdue(Task task) {
    }
}
