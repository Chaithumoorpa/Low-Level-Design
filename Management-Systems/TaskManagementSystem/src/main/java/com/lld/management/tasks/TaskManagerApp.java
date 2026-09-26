package com.lld.management.tasks;

import com.lld.management.tasks.core.ManualClock;
import com.lld.management.tasks.core.NewTask;
import com.lld.management.tasks.core.Task;
import com.lld.management.tasks.core.TaskService;
import com.lld.management.tasks.core.TaskUpdate;
import com.lld.management.tasks.model.Priority;
import com.lld.management.tasks.model.TaskException;
import com.lld.management.tasks.model.TaskStatus;
import com.lld.management.tasks.model.User;
import com.lld.management.tasks.notify.NotificationService;
import com.lld.management.tasks.search.TaskFilters;
import com.lld.management.tasks.search.TaskSort;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** One sprint week of a three-person team, on a simulated clock. */
public class TaskManagerApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2026-06-01T09:00:00Z"));   // a Monday
        TaskService service = new TaskService(clock);
        NotificationService notifications = new NotificationService();
        service.addListener(notifications);

        User maya = User.manager("maya", "Maya");
        User arjun = User.member("arjun", "Arjun");
        User lena = User.member("lena", "Lena");
        LocalDate monday = service.today();

        step("Maya plans the week");
        Task checkout = service.create(NewTask.titled("New checkout page").by(maya)
                .priority(Priority.HIGH).due(monday.plusDays(4)).tag("frontend", "payments").build());
        Task form = service.create(NewTask.titled("Card form").by(maya).under(checkout.id())
                .assignTo(lena).due(monday.plusDays(2)).tag("frontend").build());
        Task api = service.create(NewTask.titled("Payment API client").by(maya).under(checkout.id())
                .assignTo(arjun).due(monday.plusDays(3)).tag("backend").build());
        Task bug = service.create(NewTask.titled("Fix login timeout").by(arjun)
                .priority(Priority.CRITICAL).due(monday.plusDays(1)).tag("bug", "backend").build());
        service.create(NewTask.titled("Update README").by(lena).priority(Priority.LOW).build());
        printBoard(service);

        step("Arjun picks up the unassigned bug himself and starts it");
        service.assign(bug.id(), arjun, arjun);
        service.changeStatus(bug.id(), arjun, TaskStatus.IN_PROGRESS);
        service.changeStatus(form.id(), lena, TaskStatus.IN_PROGRESS);

        step("Rules that say no");
        attempt("Lena moves Arjun's API task", () -> service.changeStatus(api.id(), lena, TaskStatus.IN_PROGRESS));
        attempt("Arjun jumps TODO -> DONE", () -> service.changeStatus(api.id(), arjun, TaskStatus.DONE));
        attempt("Maya starts the parent (no assignee)", () -> service.changeStatus(checkout.id(), maya, TaskStatus.IN_PROGRESS));

        step("Two people edit the same task from stale screens");
        long seen = bug.version();
        service.update(bug.id(), maya, seen, TaskUpdate.change().description("Session expires after 5 min").addTag("auth"));
        attempt("Arjun saves his older form", () ->
                service.update(bug.id(), arjun, seen, TaskUpdate.change().priority(Priority.HIGH)));
        System.out.println("   Arjun reloads version " + bug.version() + " and saves again");
        service.update(bug.id(), arjun, bug.version(), TaskUpdate.change().priority(Priority.HIGH));

        step("Tuesday morning: reminders");
        clock.advance(Duration.ofDays(1));
        service.sendReminders().forEach(t -> System.out.println("   reminder sent for " + t.id()));
        service.comment(bug.id(), arjun, "Root cause found, @lena can you review?");
        service.changeStatus(bug.id(), arjun, TaskStatus.IN_REVIEW);

        step("Thursday: the card form is late");
        clock.advance(Duration.ofDays(2));
        service.sendReminders().forEach(t -> System.out.println("   reminder sent for " + t.id() + (t.isOverdue(service.today()) ? " (overdue)" : "")));
        service.changeStatus(form.id(), lena, TaskStatus.IN_REVIEW);
        service.changeStatus(form.id(), lena, TaskStatus.DONE);
        attempt("Maya closes the parent early", () -> {
            service.assign(checkout.id(), maya, maya);
            service.changeStatus(checkout.id(), maya, TaskStatus.IN_PROGRESS);
            service.changeStatus(checkout.id(), maya, TaskStatus.IN_REVIEW);
            service.changeStatus(checkout.id(), maya, TaskStatus.DONE);
        });
        for (TaskStatus s : List.of(TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW, TaskStatus.DONE)) {
            service.changeStatus(api.id(), arjun, s);
        }
        service.changeStatus(checkout.id(), maya, TaskStatus.DONE);
        service.changeStatus(bug.id(), arjun, TaskStatus.DONE);
        attempt("Lena reopens the finished card form", () -> service.changeStatus(form.id(), lena, TaskStatus.TODO));
        printBoard(service);

        step("Search: everything tagged backend, most urgent first");
        service.search(TaskFilters.taggedWith("backend"), TaskSort.BY_URGENCY).forEach(t -> System.out.println("   " + t));

        step("History of " + bug.id());
        bug.history().forEach(a -> System.out.println("   " + a));

        step("Inboxes");
        for (User u : List.of(maya, arjun, lena)) {
            System.out.println("   " + u.name() + ":");
            notifications.inboxOf(u).forEach(m -> System.out.println("     - " + m));
        }
    }

    private static void printBoard(TaskService service) {
        Map<TaskStatus, List<Task>> board = service.board(TaskFilters.all(), TaskSort.BY_URGENCY);
        board.forEach((status, tasks) -> {
            if (!tasks.isEmpty()) {
                System.out.println("   [" + status + "]");
                tasks.forEach(t -> System.out.println("     " + t));
            }
        });
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(String what, Runnable action) {
        try {
            action.run();
            System.out.println("   " + what + ": ok");
        } catch (TaskException e) {
            System.out.println("   " + what + ": [refused] " + e.getMessage());
        }
    }
}
