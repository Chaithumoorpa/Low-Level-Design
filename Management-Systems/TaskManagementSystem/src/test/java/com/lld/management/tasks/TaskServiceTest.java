package com.lld.management.tasks;

import com.lld.management.tasks.core.ManualClock;
import com.lld.management.tasks.core.NewTask;
import com.lld.management.tasks.core.Task;
import com.lld.management.tasks.core.TaskService;
import com.lld.management.tasks.core.TaskUpdate;
import com.lld.management.tasks.model.Priority;
import com.lld.management.tasks.model.StaleTaskException;
import com.lld.management.tasks.model.TaskException;
import com.lld.management.tasks.model.TaskStatus;
import com.lld.management.tasks.model.User;
import com.lld.management.tasks.notify.NotificationService;
import com.lld.management.tasks.search.TaskFilters;
import com.lld.management.tasks.search.TaskSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceTest {

    private final User maya = User.manager("maya", "Maya");
    private final User arjun = User.member("arjun", "Arjun");
    private final User lena = User.member("lena", "Lena");

    private ManualClock clock;
    private TaskService service;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-06-01T09:00:00Z"));
        service = new TaskService(clock);
        today = service.today();
    }

    private Task task(String title, User reporter, User assignee) {
        return service.create(NewTask.titled(title).by(reporter).assignTo(assignee).build());
    }

    private void walk(Task t, User actor, TaskStatus... path) {
        for (TaskStatus s : path) {
            service.changeStatus(t.id(), actor, s);
        }
    }

    // ------------------------------------------------------------------ creation

    @Nested
    class Creation {

        @Test
        void builderDefaultsAndIds() {
            Task t = service.create(NewTask.titled("  Write docs ").by(lena).build());
            assertEquals("TASK-1", t.id());
            assertEquals("Write docs", t.title());
            assertEquals(Priority.MEDIUM, t.priority());
            assertEquals(TaskStatus.TODO, t.status());
            assertNull(t.assignee());
            assertEquals(1, t.version());
            assertEquals("TASK-2", task("Next", lena, null).id());
        }

        @Test
        void titleAndReporterAreRequired() {
            assertThrows(IllegalArgumentException.class, () -> NewTask.titled(" "));
            assertThrows(IllegalArgumentException.class, () -> NewTask.titled("x").build());
        }

        @Test
        void tagsAreCaseInsensitive() {
            Task t = service.create(NewTask.titled("x").by(lena).tag("Backend", "BACKEND", "bug").build());
            assertEquals(2, t.tags().size());
            assertEquals(1, service.search(TaskFilters.taggedWith("BUG"), TaskSort.NEWEST_FIRST).size());
        }

        @Test
        void unknownTaskIsReported() {
            assertThrows(TaskException.class, () -> service.get("TASK-99"));
            assertThrows(TaskException.class, () -> service.create(NewTask.titled("x").by(lena).under("TASK-99").build()));
        }
    }

    // ------------------------------------------------------------------ workflow

    @Nested
    class Workflow {

        @Test
        void happyPathAndHistory() {
            Task t = task("Feature", maya, arjun);
            walk(t, arjun, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW, TaskStatus.IN_PROGRESS,
                    TaskStatus.IN_REVIEW, TaskStatus.DONE);
            assertEquals(TaskStatus.DONE, t.status());
            assertEquals(6, t.history().size());
            assertEquals("status IN_REVIEW -> DONE", t.history().get(5).change());
        }

        @ParameterizedTest
        @EnumSource(TaskStatus.class)
        void transitionTableIsEnforcedFromEveryStatus(TaskStatus from) {
            for (TaskStatus to : TaskStatus.values()) {
                Task t = task("t", maya, arjun);
                reach(t, from);
                if (from.canMoveTo(to)) {
                    service.changeStatus(t.id(), maya, to);
                    assertEquals(to, t.status());
                } else {
                    assertThrows(TaskException.class, () -> service.changeStatus(t.id(), maya, to), from + "->" + to);
                    assertEquals(from, t.status());
                }
            }
        }

        private void reach(Task t, TaskStatus target) {
            switch (target) {
                case TODO -> { }
                case IN_PROGRESS -> walk(t, maya, TaskStatus.IN_PROGRESS);
                case IN_REVIEW -> walk(t, maya, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW);
                case DONE -> walk(t, maya, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW, TaskStatus.DONE);
                case CANCELLED -> walk(t, maya, TaskStatus.CANCELLED);
            }
        }

        @Test
        void closedStatusesOnlyReopenToTodo() {
            for (TaskStatus s : TaskStatus.values()) {
                assertEquals(s.isClosed(), EnumSet.of(TaskStatus.DONE, TaskStatus.CANCELLED).contains(s));
            }
            assertEquals(EnumSet.of(TaskStatus.TODO), TaskStatus.DONE.next());
            assertEquals(EnumSet.of(TaskStatus.TODO), TaskStatus.CANCELLED.next());
        }

        @Test
        void startingNeedsAnAssignee() {
            Task t = task("t", maya, null);
            assertThrows(TaskException.class, () -> service.changeStatus(t.id(), maya, TaskStatus.IN_PROGRESS));
            service.assign(t.id(), maya, lena);
            service.changeStatus(t.id(), lena, TaskStatus.IN_PROGRESS);
            assertThrows(TaskException.class, () -> service.assign(t.id(), maya, null), "in progress needs an owner");
        }
    }

    // ------------------------------------------------------------------ permissions

    @Nested
    class Permissions {

        @Test
        void onlyReporterAssigneeOrManagerMayChange() {
            Task t = task("t", arjun, arjun);
            assertThrows(TaskException.class, () -> service.changeStatus(t.id(), lena, TaskStatus.IN_PROGRESS));
            assertThrows(TaskException.class, () -> service.update(t.id(), lena, t.version(), TaskUpdate.change().title("x")));
            assertThrows(TaskException.class, () -> service.assign(t.id(), lena, lena));
            service.changeStatus(t.id(), maya, TaskStatus.IN_PROGRESS);        // manager
            assertEquals(TaskStatus.IN_PROGRESS, t.status());
        }

        @Test
        void anyoneMayPickUpAnUnassignedTaskForThemselvesOnly() {
            Task t = task("t", maya, null);
            assertThrows(TaskException.class, () -> service.assign(t.id(), lena, arjun));
            service.assign(t.id(), lena, lena);
            assertEquals(lena, t.assignee());
            assertThrows(TaskException.class, () -> service.assign(t.id(), arjun, arjun), "no longer unassigned");
        }

        @Test
        void anyoneMayComment() {
            Task t = task("t", maya, arjun);
            service.comment(t.id(), lena, "I can help");
            assertEquals(1, t.comments().size());
            assertThrows(TaskException.class, () -> service.comment(t.id(), lena, "  "));
        }

        @Test
        void closedTasksCantBeReassigned() {
            Task t = task("t", maya, arjun);
            walk(t, maya, TaskStatus.CANCELLED);
            assertThrows(TaskException.class, () -> service.assign(t.id(), maya, lena));
        }
    }

    // ------------------------------------------------------------------ subtasks

    @Nested
    class Subtasks {

        @Test
        void parentCantBeDoneWhileSubtasksAreOpen() {
            Task parent = task("Epic", maya, maya);
            Task a = service.create(NewTask.titled("A").by(maya).assignTo(arjun).under(parent.id()).build());
            Task b = service.create(NewTask.titled("B").by(maya).assignTo(lena).under(parent.id()).build());
            assertEquals(List.of(a.id(), b.id()), parent.subtaskIds());

            walk(parent, maya, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW);
            walk(a, arjun, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW, TaskStatus.DONE);
            TaskException e = assertThrows(TaskException.class, () -> service.changeStatus(parent.id(), maya, TaskStatus.DONE));
            assertTrue(e.getMessage().contains(b.id()));

            walk(b, lena, TaskStatus.CANCELLED);                              // cancelled counts as closed
            service.changeStatus(parent.id(), maya, TaskStatus.DONE);
            assertEquals(TaskStatus.DONE, parent.status());
        }

        @Test
        void noSubtasksUnderAClosedParentAndNoReopeningUnderIt() {
            Task parent = task("Epic", maya, maya);
            Task a = service.create(NewTask.titled("A").by(maya).assignTo(arjun).under(parent.id()).build());
            walk(a, arjun, TaskStatus.CANCELLED);
            walk(parent, maya, TaskStatus.CANCELLED);
            assertThrows(TaskException.class, () -> service.create(NewTask.titled("B").by(maya).under(parent.id()).build()));
            assertEquals(2, service.size());
            assertThrows(TaskException.class, () -> service.changeStatus(a.id(), arjun, TaskStatus.TODO));

            service.changeStatus(parent.id(), maya, TaskStatus.TODO);
            service.changeStatus(a.id(), arjun, TaskStatus.TODO);
            assertEquals(TaskStatus.TODO, a.status());
        }
    }

    // ------------------------------------------------------------------ editing

    @Nested
    class Editing {

        @Test
        void partialUpdateChangesOnlyGivenFields() {
            Task t = service.create(NewTask.titled("Old").by(arjun).description("d").priority(Priority.LOW)
                    .due(today.plusDays(3)).tag("a").build());
            service.update(t.id(), arjun, t.version(),
                    TaskUpdate.change().title("New").priority(Priority.HIGH).addTag("b").removeTag("a").noDueDate());
            assertEquals("New", t.title());
            assertEquals("d", t.description());
            assertEquals(Priority.HIGH, t.priority());
            assertEquals(java.util.Set.of("b"), t.tags());
            assertNull(t.dueDate());
            assertEquals(6, t.version(), "created + 5 changes");
        }

        @Test
        void staleVersionIsRejectedAndChangesNothing() {
            Task t = task("t", arjun, arjun);
            long seen = t.version();
            service.update(t.id(), maya, seen, TaskUpdate.change().priority(Priority.CRITICAL));
            assertThrows(StaleTaskException.class,
                    () -> service.update(t.id(), arjun, seen, TaskUpdate.change().priority(Priority.LOW).title("mine")));
            assertEquals(Priority.CRITICAL, t.priority());
            assertEquals("t", t.title());
        }

        @Test
        void commentsDoNotMakeAFormStale() {
            Task t = task("t", arjun, arjun);
            long seen = t.version();
            service.comment(t.id(), lena, "hello");
            service.update(t.id(), arjun, seen, TaskUpdate.change().title("still fine"));
            assertEquals("still fine", t.title());
        }

        @Test
        void noOpEditDoesNotBumpTheVersion() {
            Task t = service.create(NewTask.titled("Same").by(arjun).priority(Priority.HIGH).build());
            service.update(t.id(), arjun, 1, TaskUpdate.change().title("Same").priority(Priority.HIGH));
            assertEquals(1, t.version());
        }
    }

    // ------------------------------------------------------------------ search

    @Test
    void filtersComposeAndSortsAreStable() {
        Task low = service.create(NewTask.titled("Low").by(maya).assignTo(arjun).priority(Priority.LOW).due(today.plusDays(1)).build());
        Task highLate = service.create(NewTask.titled("High late").by(maya).assignTo(arjun).priority(Priority.HIGH).due(today.plusDays(9)).build());
        Task highSoon = service.create(NewTask.titled("High soon").by(maya).assignTo(arjun).priority(Priority.HIGH).due(today.plusDays(2)).build());
        Task noDate = service.create(NewTask.titled("No date").by(maya).assignTo(lena).priority(Priority.CRITICAL).description("login bug").build());
        walk(low, arjun, TaskStatus.CANCELLED);

        assertEquals(List.of(noDate, highSoon, highLate, low), service.search(TaskFilters.all(), TaskSort.BY_URGENCY));
        assertEquals(List.of(low, highSoon, highLate, noDate), service.search(TaskFilters.all(), TaskSort.BY_DUE_DATE));
        assertEquals(List.of(highSoon, highLate),
                service.search(TaskFilters.assignedTo(arjun).and(TaskFilters.open()), TaskSort.BY_URGENCY));
        assertEquals(List.of(noDate), service.search(TaskFilters.text("LOGIN"), TaskSort.NEWEST_FIRST));
        assertEquals(List.of(highSoon), service.search(
                TaskFilters.priorityAtLeast(Priority.HIGH).and(TaskFilters.dueBy(today.plusDays(5))), TaskSort.BY_URGENCY));
        assertEquals(1, service.board(TaskFilters.all(), TaskSort.BY_URGENCY).get(TaskStatus.CANCELLED).size());
    }

    // ------------------------------------------------------------------ reminders & notifications

    @Test
    void remindersFireOncePerDayAndOnlyForOpenAssignedTasks() {
        Task soon = service.create(NewTask.titled("Soon").by(maya).assignTo(arjun).due(today.plusDays(1)).build());
        Task later = service.create(NewTask.titled("Later").by(maya).assignTo(arjun).due(today.plusDays(5)).build());
        service.create(NewTask.titled("Nobody").by(maya).due(today).build());
        Task done = service.create(NewTask.titled("Done").by(maya).assignTo(lena).due(today).build());
        walk(done, lena, TaskStatus.CANCELLED);

        assertEquals(List.of(soon), service.sendReminders());
        assertEquals(List.of(), service.sendReminders(), "same day: no repeat");

        clock.advance(Duration.ofDays(2));
        assertTrue(soon.isOverdue(service.today()));
        assertEquals(List.of(soon), service.sendReminders());
        clock.advance(Duration.ofDays(2));
        assertEquals(List.of(soon, later), service.sendReminders(), "overdue again + later is due tomorrow");
        assertFalse(later.isOverdue(service.today()));
    }

    @Test
    void notificationsGoToInvolvedPeopleButNeverTheActor() {
        NotificationService inbox = new NotificationService();
        service.addListener(inbox);
        Task t = task("t", maya, arjun);                           // Maya assigns Arjun
        service.changeStatus(t.id(), arjun, TaskStatus.IN_PROGRESS);
        service.comment(t.id(), arjun, "@lena please review, @maya FYI, @arjun note to self");
        service.assign(t.id(), maya, lena);

        assertEquals(List.of("Maya assigned you TASK-1 't'", "TASK-1 was reassigned to Lena"), inbox.inboxOf(arjun));
        assertEquals(List.of("Arjun moved TASK-1 TODO -> IN_PROGRESS", "Arjun commented on TASK-1"), inbox.inboxOf(maya),
                "no duplicate for the @maya mention");
        assertEquals(List.of("Arjun mentioned you on TASK-1", "Maya assigned you TASK-1 't'"), inbox.inboxOf(lena));
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentEditsWithTheSameVersionHaveExactlyOneWinner() throws Exception {
        Task t = task("t", maya, arjun);
        long seen = t.version();
        int editors = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stale = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < editors; i++) {
            String title = "edit " + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.update(t.id(), maya, seen, TaskUpdate.change().title(title));
                } catch (StaleTaskException e) {
                    stale.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(editors - 1, stale.get());
        assertEquals(seen + 1, t.version());
    }

    @Test
    void parentAndChildChangesRaceWithoutDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int round = 0; round < 50; round++) {
            Task parent = task("Epic " + round, maya, maya);
            Task child = service.create(NewTask.titled("child").by(maya).assignTo(arjun).under(parent.id()).build());
            walk(parent, maya, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW);
            walk(child, arjun, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW);
            Future<?> closeChild = pool.submit(() -> service.changeStatus(child.id(), arjun, TaskStatus.DONE));
            Future<?> closeParent = pool.submit(() -> {
                try {
                    service.changeStatus(parent.id(), maya, TaskStatus.DONE);
                } catch (TaskException childStillOpen) {
                    // lost the race; fine
                }
            });
            closeChild.get(5, TimeUnit.SECONDS);
            closeParent.get(5, TimeUnit.SECONDS);
            assertEquals(TaskStatus.DONE, child.status());
            if (parent.status() == TaskStatus.DONE) {
                assertTrue(child.status().isClosed(), "a parent is only DONE after its children");
            }
        }
        pool.shutdown();
    }
}
