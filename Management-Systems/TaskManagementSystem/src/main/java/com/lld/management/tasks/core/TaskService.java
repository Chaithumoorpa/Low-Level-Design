package com.lld.management.tasks.core;

import com.lld.management.tasks.model.Comment;
import com.lld.management.tasks.model.StaleTaskException;
import com.lld.management.tasks.model.TaskException;
import com.lld.management.tasks.model.TaskStatus;
import com.lld.management.tasks.model.User;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Facade over the task store. Every write goes through here so the rules live in one place:
 * <ul>
 *   <li><b>permissions</b>: managers change anything; members only tasks they reported or own,</li>
 *   <li><b>workflow</b>: {@link TaskStatus#canMoveTo}; IN_PROGRESS needs an assignee,</li>
 *   <li><b>hierarchy</b>: a parent can't be DONE while a subtask is open; a subtask can't be reopened
 *       under a finished parent,</li>
 *   <li><b>optimistic locking</b> for form edits ({@link #update}).</li>
 * </ul>
 *
 * <p>Locking: one monitor per task. When two tasks are involved, the <b>parent is always locked
 * before the child</b>, so the two can never wait on each other (no deadlock).
 */
public final class TaskService {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final List<TaskEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> remindersSent = ConcurrentHashMap.newKeySet();
    private final Clock clock;

    public TaskService(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public void addListener(TaskEventListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ create

    public Task create(NewTask request) {
        Instant now = clock.instant();
        Task task = new Task(sequence.incrementAndGet(), request, now);
        if (request.parentId != null) {
            Task parent = get(request.parentId);
            synchronized (parent) {
                if (parent.status().isClosed()) {
                    throw new TaskException("Can't add a subtask to " + parent.id() + ": it is " + parent.status());
                }
                tasks.put(task.id(), task);                 // visible before the parent lists it
                parent.addSubtask(task.id(), request.reporter, now);
            }
        } else {
            tasks.put(task.id(), task);
        }
        listeners.forEach(l -> l.onCreated(task));
        if (request.assignee != null) {
            listeners.forEach(l -> l.onAssigned(task, null, request.assignee, request.reporter));
        }
        return task;
    }

    // ------------------------------------------------------------------ assign

    /**
     * Reassigns (or unassigns with {@code null}). Allowed for people who may change the task, and
     * for anyone picking up an <em>unassigned</em> task for themselves.
     */
    public void assign(String taskId, User actor, User assignee) {
        Task task = get(taskId);
        User previous;
        synchronized (task) {
            boolean selfPickup = task.assignee() == null && actor.equals(assignee);
            if (!selfPickup) {
                requireCanChange(task, actor);
            }
            if (task.status().isClosed()) {
                throw new TaskException(taskId + " is " + task.status() + "; reopen it before reassigning");
            }
            if (assignee == null && task.status() == TaskStatus.IN_PROGRESS) {
                throw new TaskException(taskId + " is in progress; someone has to own it");
            }
            previous = task.assignee();
            if (Objects.equals(previous, assignee)) {
                return;
            }
            task.setAssignee(assignee, actor, clock.instant());
        }
        listeners.forEach(l -> l.onAssigned(task, previous, assignee, actor));
    }

    // ------------------------------------------------------------------ workflow

    public void changeStatus(String taskId, User actor, TaskStatus target) {
        Task task = get(taskId);
        TaskStatus from;
        if (task.parentId() == null) {
            synchronized (task) {
                from = applyStatus(task, null, actor, target);
            }
        } else {
            Task parent = get(task.parentId());
            synchronized (parent) {                          // parent first, always
                synchronized (task) {
                    from = applyStatus(task, parent, actor, target);
                }
            }
        }
        listeners.forEach(l -> l.onStatusChanged(task, from, target, actor));
    }

    private TaskStatus applyStatus(Task task, Task parent, User actor, TaskStatus target) {
        requireCanChange(task, actor);
        TaskStatus from = task.status();
        if (!from.canMoveTo(target)) {
            throw new TaskException("Can't move " + task.id() + " from " + from + " to " + target
                    + " (allowed: " + from.next() + ")");
        }
        if (target == TaskStatus.IN_PROGRESS && task.assignee() == null) {
            throw new TaskException("Assign " + task.id() + " before starting it");
        }
        if (target == TaskStatus.DONE) {
            List<String> open = task.subtaskIds().stream()
                    .filter(id -> !get(id).status().isClosed()).toList();
            if (!open.isEmpty()) {
                throw new TaskException(task.id() + " has open subtasks " + open);
            }
        }
        if (parent != null && from.isClosed() && parent.status().isClosed()) {
            throw new TaskException("Parent " + parent.id() + " is " + parent.status() + "; reopen it first");
        }
        task.setStatus(target, actor, clock.instant());
        return from;
    }

    // ------------------------------------------------------------------ edit

    /**
     * Applies a form edit only if nobody changed the task since the user loaded it
     * ({@code expectedVersion}); otherwise throws {@link StaleTaskException} and changes nothing.
     */
    public Task update(String taskId, User actor, long expectedVersion, TaskUpdate change) {
        Task task = get(taskId);
        synchronized (task) {
            requireCanChange(task, actor);
            if (task.version() != expectedVersion) {
                throw new StaleTaskException(taskId, expectedVersion, task.version());
            }
            Instant now = clock.instant();
            if (change.title != null && !change.title.equals(task.title())) {
                task.setTitle(change.title, actor, now);
            }
            if (change.description != null && !change.description.equals(task.description())) {
                task.setDescription(change.description, actor, now);
            }
            if (change.priority != null && change.priority != task.priority()) {
                task.setPriority(change.priority, actor, now);
            }
            if (change.clearDueDate && task.dueDate() != null) {
                task.setDueDate(null, actor, now);
            } else if (change.dueDate != null && !change.dueDate.equals(task.dueDate())) {
                task.setDueDate(change.dueDate, actor, now);
            }
            change.addTags.forEach(t -> task.addTag(t, actor, now));
            change.removeTags.forEach(t -> task.removeTag(t, actor, now));
        }
        return task;
    }

    public Comment comment(String taskId, User author, String text) {
        if (text == null || text.isBlank()) {
            throw new TaskException("Empty comment");
        }
        Task task = get(taskId);
        Comment comment = new Comment(author, text.strip(), clock.instant());
        task.addComment(comment);
        listeners.forEach(l -> l.onCommented(task, comment));
        return comment;
    }

    // ------------------------------------------------------------------ reminders

    /**
     * Run by a scheduler (e.g. every hour). Fires {@code onOverdue} / {@code onDueSoon} (due today or
     * tomorrow) for open, assigned tasks, at most once per task per kind per day.
     *
     * @return the tasks that got a reminder this run
     */
    public List<Task> sendReminders() {
        LocalDate today = LocalDate.now(clock);
        List<Task> reminded = new ArrayList<>();
        for (Task task : tasks.values()) {
            LocalDate due = task.dueDate();
            if (due == null || task.status().isClosed() || task.assignee() == null) {
                continue;
            }
            if (task.isOverdue(today)) {
                if (remindersSent.add(task.id() + "|overdue|" + today)) {
                    listeners.forEach(l -> l.onOverdue(task));
                    reminded.add(task);
                }
            } else if (!due.isAfter(today.plusDays(1))) {
                if (remindersSent.add(task.id() + "|soon|" + today)) {
                    listeners.forEach(l -> l.onDueSoon(task));
                    reminded.add(task);
                }
            }
        }
        reminded.sort(Comparator.comparingLong(Task::number));
        return reminded;
    }

    // ------------------------------------------------------------------ queries

    public Task get(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            throw new TaskException("No task " + taskId);
        }
        return task;
    }

    /** Filter + sort: filters come from {@code TaskFilters}, orders from {@code TaskSort}. */
    public List<Task> search(Predicate<Task> filter, Comparator<Task> order) {
        return tasks.values().stream().filter(filter).sorted(order).toList();
    }

    /** Kanban view: tasks grouped by status, most urgent first in every column. */
    public Map<TaskStatus, List<Task>> board(Predicate<Task> filter, Comparator<Task> order) {
        Map<TaskStatus, List<Task>> columns = new EnumMap<>(TaskStatus.class);
        for (TaskStatus s : TaskStatus.values()) {
            columns.put(s, new ArrayList<>());
        }
        search(filter, order).forEach(t -> columns.get(t.status()).add(t));
        return columns;
    }

    public int size() {
        return tasks.size();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private static void requireCanChange(Task task, User actor) {
        if (!task.canBeChangedBy(actor)) {
            throw new TaskException(actor.name() + " may not change " + task.id()
                    + " (only its reporter, assignee or a manager)");
        }
    }
}
