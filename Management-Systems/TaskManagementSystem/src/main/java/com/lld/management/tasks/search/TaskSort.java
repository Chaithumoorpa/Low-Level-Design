package com.lld.management.tasks.search;

import com.lld.management.tasks.core.Task;

import java.util.Comparator;

/** Ready-made orders (Strategy). Each ends with the task number so results are always stable. */
public final class TaskSort {

    private TaskSort() {
    }

    private static final Comparator<Task> DUE_DATE_NULLS_LAST =
            Comparator.comparing(Task::dueDate, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Highest priority first; within a priority, earliest due date first. */
    public static final Comparator<Task> BY_URGENCY = Comparator.comparing(Task::priority).reversed()
            .thenComparing(DUE_DATE_NULLS_LAST)
            .thenComparingLong(Task::number);

    /** Earliest due date first (no due date last); ties broken by priority. */
    public static final Comparator<Task> BY_DUE_DATE = DUE_DATE_NULLS_LAST
            .thenComparing(Comparator.comparing(Task::priority).reversed())
            .thenComparingLong(Task::number);

    public static final Comparator<Task> NEWEST_FIRST = Comparator.comparingLong(Task::number).reversed();
}
