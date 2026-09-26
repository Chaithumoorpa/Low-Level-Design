package com.lld.management.tasks.notify;

import com.lld.management.tasks.core.Task;
import com.lld.management.tasks.core.TaskEventListener;
import com.lld.management.tasks.model.Comment;
import com.lld.management.tasks.model.TaskStatus;
import com.lld.management.tasks.model.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app inbox per user, fed by task events (Observer). Decides <em>who</em> hears about a change:
 * the people involved (reporter, assignee, previous assignee, @mentioned users), never the person
 * who made the change. Delivery channels (e-mail, push) would be further listeners.
 */
public final class NotificationService implements TaskEventListener {

    private static final Pattern MENTION = Pattern.compile("@(\\w+)");

    private final Map<String, List<String>> inbox = new ConcurrentHashMap<>();

    @Override
    public void onAssigned(Task task, User from, User to, User actor) {
        if (to != null) {
            send(to, actor, actor.name() + " assigned you " + task.id() + " '" + task.title() + "'");
        }
        if (from != null) {
            send(from, actor, task.id() + " was reassigned to " + (to == null ? "nobody" : to.name()));
        }
    }

    @Override
    public void onStatusChanged(Task task, TaskStatus from, TaskStatus to, User actor) {
        String text = actor.name() + " moved " + task.id() + " " + from + " -> " + to;
        for (User u : involved(task)) {
            send(u, actor, text);
        }
    }

    @Override
    public void onCommented(Task task, Comment comment) {
        Set<String> notified = new LinkedHashSet<>();
        for (User u : involved(task)) {
            if (send(u, comment.author(), comment.author().name() + " commented on " + task.id())) {
                notified.add(u.id());
            }
        }
        Matcher m = MENTION.matcher(comment.text());
        while (m.find()) {
            String userId = m.group(1);
            if (!userId.equals(comment.author().id()) && notified.add(userId)) {
                deliver(userId, comment.author().name() + " mentioned you on " + task.id());
            }
        }
    }

    @Override
    public void onDueSoon(Task task) {
        deliver(task.assignee().id(), "Reminder: " + task.id() + " is due " + task.dueDate());
    }

    @Override
    public void onOverdue(Task task) {
        deliver(task.assignee().id(), "OVERDUE: " + task.id() + " was due " + task.dueDate());
        if (!task.reporter().equals(task.assignee())) {
            deliver(task.reporter().id(), "OVERDUE: " + task.id() + " (" + task.assignee().name() + ") was due " + task.dueDate());
        }
    }

    public List<String> inboxOf(User user) {
        List<String> messages = inbox.getOrDefault(user.id(), List.of());
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    private static Set<User> involved(Task task) {
        Set<User> people = new LinkedHashSet<>();
        people.add(task.reporter());
        if (task.assignee() != null) {
            people.add(task.assignee());
        }
        return people;
    }

    /** @return true if delivered (false when the recipient is the actor) */
    private boolean send(User to, User actor, String text) {
        if (to.equals(actor)) {
            return false;
        }
        deliver(to.id(), text);
        return true;
    }

    private void deliver(String userId, String text) {
        inbox.computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>())).add(text);
    }
}
