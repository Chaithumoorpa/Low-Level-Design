package com.lld.management.library;

import com.lld.management.library.core.LibraryListener;
import com.lld.management.library.core.LibraryService;
import com.lld.management.library.core.ManualClock;
import com.lld.management.library.model.Book;
import com.lld.management.library.model.BookCopy;
import com.lld.management.library.model.Hold;
import com.lld.management.library.model.LibraryException;
import com.lld.management.library.model.Loan;
import com.lld.management.library.model.MemberType;
import com.lld.management.library.policy.DailyFinePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryServiceTest {

    private static final String EJ = "978-0134685991";
    private static final String CC = "978-0132350884";
    private static final String DP = "978-0201633610";

    private ManualClock clock;
    private LibraryService lib;
    private final List<String> notices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-09-01T10:00:00Z"));
        lib = new LibraryService(DailyFinePolicy.standard(), 3, clock);
        lib.addListener(new LibraryListener() {
            @Override
            public void onHoldReady(Hold hold) {
                notices.add("ready " + hold.member().id() + " " + hold.copy().barcode());
            }

            @Override
            public void onHoldExpired(Hold hold) {
                notices.add("expired " + hold.member().id());
            }

            @Override
            public void onDueSoon(Loan loan) {
                notices.add("soon " + loan.id());
            }

            @Override
            public void onOverdue(Loan loan, long daysLate) {
                notices.add("late " + loan.id() + " " + daysLate);
            }
        });
        lib.addBook(new Book(EJ, "Effective Java", List.of("Joshua Bloch"), "Programming", 2018, 4500));
        lib.addBook(new Book(CC, "Clean Code", List.of("Robert C. Martin"), "Programming", 2008, 3800));
        lib.addBook(new Book(DP, "Design Patterns", List.of("Erich Gamma", "Ralph Johnson"), "Software Design", 1994, 5000));
        lib.addCopy("EJ-1", EJ, "A1");
        lib.addCopy("CC-1", CC, "A2");
        lib.addCopy("CC-2", CC, "A2");
        lib.addCopy("DP-1", DP, "B1");
        lib.register("s1", "Priya", MemberType.STUDENT);
        lib.register("s2", "Omar", MemberType.STUDENT);
        lib.register("f1", "Chen", MemberType.FACULTY);
        lib.register("g1", "Guest", MemberType.GUEST);
    }

    private void days(int n) {
        clock.advance(Duration.ofDays(n));
    }

    private LocalDate day(int n) {
        return LocalDate.of(2026, 9, 1).plusDays(n);
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    void searchMatchesAllWordsInAnyOrderAndField() {
        assertEquals(List.of("Design Patterns"), titles(lib.search("patterns design")));
        assertEquals(List.of("Clean Code", "Effective Java"), titles(lib.search("PROGRAMMING")));
        assertEquals(List.of("Clean Code"), titles(lib.search("martin programming")));
        assertEquals(List.of("Design Patterns"), titles(lib.searchByAuthor("johnson gamma")));
        assertEquals(List.of(), lib.searchByTitle("martin"), "author words don't match a title search");
        assertEquals(List.of(), lib.search("design java"), "AND, not OR");
        assertEquals(List.of(), lib.search("  "));
        assertEquals(List.of("Design Patterns"), titles(lib.searchBySubject("software")));
    }

    private static List<String> titles(List<Book> books) {
        return books.stream().map(Book::title).toList();
    }

    // ------------------------------------------------------------------ checkout rules

    @Nested
    class Checkout {

        @Test
        void loanPeriodDependsOnMemberType() {
            assertEquals(day(14), lib.checkout("s1", "EJ-1").dueDate());
            assertEquals(day(30), lib.checkout("f1", "DP-1").dueDate());
            assertEquals(day(7), lib.checkout("g1", "CC-1").dueDate());
            assertEquals(BookCopy.Status.ON_LOAN, lib.copy("EJ-1").status());
        }

        @Test
        void loanLimitPerType() {
            lib.checkout("g1", "EJ-1");
            LibraryException e = assertThrows(LibraryException.class, () -> lib.checkout("g1", "DP-1"));
            assertTrue(e.getMessage().contains("limit 1"));
        }

        @Test
        void oneCopyOfATitlePerMember() {
            lib.checkout("s1", "CC-1");
            assertThrows(LibraryException.class, () -> lib.checkout("s1", "CC-2"));
        }

        @Test
        void copyMustBeFree() {
            lib.checkout("s1", "EJ-1");
            assertThrows(LibraryException.class, () -> lib.checkout("s2", "EJ-1"));
            assertThrows(LibraryException.class, () -> lib.checkout("s2", "NOPE"));
            assertThrows(LibraryException.class, () -> lib.checkout("x9", "CC-1"));
        }

        @Test
        void suspendedOverdueOrIndebtedMembersAreBlocked() {
            lib.setSuspended("s2", true);
            assertThrows(LibraryException.class, () -> lib.checkout("s2", "CC-1"));
            lib.setSuspended("s2", false);
            lib.checkout("s2", "CC-1");

            lib.checkout("s1", "EJ-1");
            days(15);                                                         // EJ-1 is 1 day late
            LibraryException overdue = assertThrows(LibraryException.class, () -> lib.checkout("s1", "DP-1"));
            assertTrue(overdue.getMessage().contains("overdue"));

            lib.reportLost("EJ-1");                                           // $45 + $2
            LibraryException owes = assertThrows(LibraryException.class, () -> lib.checkout("s1", "DP-1"));
            assertTrue(owes.getMessage().contains("owes $47.00"), owes.getMessage());
            lib.payFine("s1", 4500);
            lib.checkout("s1", "DP-1");                                      // $2 left, under the $5 threshold
            assertEquals(200, lib.member("s1").finesDueCents());
        }
    }

    // ------------------------------------------------------------------ returns and fines

    @ParameterizedTest(name = "returned {0} day(s) after due -> {1}c")
    @CsvSource({"-3,0", "0,0", "1,0", "2,50", "10,250", "40,1000", "400,1000"})
    void lateFinesHaveGraceAndCap(int daysAfterDue, long expectedFine) {
        Loan loan = lib.checkout("s1", "EJ-1");
        days(14 + daysAfterDue);
        Loan closed = lib.returnCopy("EJ-1");
        assertEquals(expectedFine, closed.fineCents());
        assertEquals(expectedFine, lib.member("s1").finesDueCents());
        assertEquals(BookCopy.Status.AVAILABLE, loan.copy().status());
    }

    @Test
    void returningSomethingNotOnLoanFails() {
        assertThrows(LibraryException.class, () -> lib.returnCopy("EJ-1"));
    }

    @Test
    void lostBookChargesPricePlusHandlingPlusLateFine() {
        lib.checkout("s1", "CC-1");
        days(24);                                                            // 10 days late
        Loan loan = lib.reportLost("CC-1");
        assertEquals(3800 + 200 + 250, loan.fineCents());
        assertEquals(BookCopy.Status.LOST, lib.copy("CC-1").status());
        assertThrows(LibraryException.class, () -> lib.checkout("s2", "CC-1"));
        assertEquals(1, lib.availableCopies(CC));
    }

    @Test
    void finePaymentsMustBeWithinTheBalance() {
        lib.checkout("s1", "CC-1");
        days(20);
        lib.returnCopy("CC-1");                                              // 6 days late: $1.50
        assertThrows(LibraryException.class, () -> lib.payFine("s1", 151));
        assertThrows(LibraryException.class, () -> lib.payFine("s1", 0));
        lib.payFine("s1", 150);
        assertEquals(0, lib.member("s1").finesDueCents());
    }

    // ------------------------------------------------------------------ renewals

    @Nested
    class Renewals {

        @Test
        void renewExtendsFromTodayUpToTheLimit() {
            lib.checkout("s1", "EJ-1");
            days(10);
            assertEquals(day(24), lib.renew("s1", "EJ-1").dueDate());
            assertThrows(LibraryException.class, () -> lib.renew("s1", "EJ-1"), "students renew once");
        }

        @Test
        void renewNeverShortensALoan() {
            lib.checkout("f1", "EJ-1");                                        // due day 30
            assertEquals(day(30), lib.renew("f1", "EJ-1").dueDate());
        }

        @Test
        void noRenewalWhenLateSomeoneWaitsOrNotYours() {
            lib.checkout("s1", "EJ-1");
            assertThrows(LibraryException.class, () -> lib.renew("s2", "EJ-1"));
            lib.placeHold("s2", EJ);
            assertThrows(LibraryException.class, () -> lib.renew("s1", "EJ-1"));

            lib.checkout("s1", "DP-1");
            days(15);
            assertThrows(LibraryException.class, () -> lib.renew("s1", "DP-1"));
            assertThrows(LibraryException.class, () -> lib.renew("g1", "DP-1"));
        }
    }

    // ------------------------------------------------------------------ holds

    @Nested
    class Holds {

        @Test
        void returnedCopyGoesToTheFirstInLine() {
            lib.checkout("s1", "EJ-1");
            Hold omar = lib.placeHold("s2", EJ);
            Hold chen = lib.placeHold("f1", EJ);
            assertEquals(List.of(omar, chen), lib.holdQueue(EJ));

            lib.returnCopy("EJ-1");
            assertEquals(BookCopy.Status.ON_HOLD_SHELF, lib.copy("EJ-1").status());
            assertEquals(Hold.Status.READY, omar.status());
            assertEquals(List.of("ready s2 EJ-1"), notices);
            assertEquals(List.of(chen), lib.holdQueue(EJ));
            assertEquals(0, lib.availableCopies(EJ));

            assertThrows(LibraryException.class, () -> lib.checkout("f1", "EJ-1"), "not Chen's turn");
            lib.checkout("s2", "EJ-1");
            assertEquals(Hold.Status.FULFILLED, omar.status());
        }

        @Test
        void uncollectedHoldPassesToTheNextMemberThenTheShelf() {
            lib.checkout("s1", "EJ-1");
            Hold omar = lib.placeHold("s2", EJ);
            Hold chen = lib.placeHold("f1", EJ);
            lib.returnCopy("EJ-1");
            days(3);
            assertEquals(List.of(), lib.expireHolds(), "pick-up day itself is still fine");
            days(1);
            assertEquals(List.of(omar), lib.expireHolds());
            assertEquals(Hold.Status.EXPIRED, omar.status());
            assertEquals(Hold.Status.READY, chen.status());
            days(4);
            lib.expireHolds();
            assertEquals(BookCopy.Status.AVAILABLE, lib.copy("EJ-1").status());
            assertEquals(List.of("ready s2 EJ-1", "expired s2", "ready f1 EJ-1", "expired f1"), notices);
        }

        @Test
        void cancellingAReadyHoldMovesTheCopyOn() {
            lib.checkout("s1", "EJ-1");
            Hold omar = lib.placeHold("s2", EJ);
            Hold chen = lib.placeHold("f1", EJ);
            lib.returnCopy("EJ-1");
            lib.cancelHold(omar.id());
            assertEquals(Hold.Status.CANCELLED, omar.status());
            assertEquals(Hold.Status.READY, chen.status());
            assertThrows(LibraryException.class, () -> lib.cancelHold(omar.id()));
        }

        @Test
        void cancellingAWaitingHoldLeavesTheQueue() {
            lib.checkout("s1", "EJ-1");
            Hold omar = lib.placeHold("s2", EJ);
            Hold chen = lib.placeHold("f1", EJ);
            lib.cancelHold(omar.id());
            assertEquals(List.of(chen), lib.holdQueue(EJ));
        }

        @Test
        void holdRules() {
            assertThrows(LibraryException.class, () -> lib.placeHold("s2", EJ), "copy is on the shelf");
            lib.checkout("s1", "EJ-1");
            assertThrows(LibraryException.class, () -> lib.placeHold("s1", EJ), "already has it");
            lib.placeHold("s2", EJ);
            assertThrows(LibraryException.class, () -> lib.placeHold("s2", EJ), "duplicate");
            assertThrows(LibraryException.class, () -> lib.placeHold("s2", "999"), "unknown book");
            lib.reportLost("EJ-1");
            assertThrows(LibraryException.class, () -> lib.placeHold("f1", EJ), "no copies left");
        }

        @Test
        void newCopyServesTheQueueImmediately() {
            lib.checkout("s1", "EJ-1");
            Hold omar = lib.placeHold("s2", EJ);
            lib.addCopy("EJ-2", EJ, "A1");
            assertEquals(Hold.Status.READY, omar.status());
            assertEquals("EJ-2", omar.copy().barcode());
        }
    }

    // ------------------------------------------------------------------ notices

    @Test
    void noticesOncePerDay() {
        Loan l1 = lib.checkout("s1", "EJ-1");                                  // due day 14
        days(13);
        assertEquals(1, lib.sendNotices());
        assertEquals(0, lib.sendNotices());
        days(3);
        assertEquals(1, lib.sendNotices());
        assertEquals(0, lib.sendNotices());
        days(1);
        assertEquals(1, lib.sendNotices());
        assertEquals(List.of("soon " + l1.id(), "late " + l1.id() + " 2", "late " + l1.id() + " 3"), notices);
        assertEquals(List.of(l1), lib.overdueLoans());
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void twoMembersScanTheLastCopyAtOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            String barcode = "X-" + round;
            lib.addCopy(barcode, DP, "B2");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (String member : List.of("s1", "s2")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        lib.checkout(member, barcode);
                        wins.incrementAndGet();
                    } catch (LibraryException lost) {
                        // the other one got it
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(1, wins.get());
            lib.returnCopy(barcode);
        }
    }
}
