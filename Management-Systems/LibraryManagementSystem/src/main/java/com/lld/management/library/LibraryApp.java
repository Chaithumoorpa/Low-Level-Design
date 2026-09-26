package com.lld.management.library;

import com.lld.management.library.core.LibraryListener;
import com.lld.management.library.core.LibraryService;
import com.lld.management.library.core.ManualClock;
import com.lld.management.library.model.Book;
import com.lld.management.library.model.Hold;
import com.lld.management.library.model.LibraryException;
import com.lld.management.library.model.Loan;
import com.lld.management.library.model.MemberType;
import com.lld.management.library.policy.DailyFinePolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** A month at a small university library, on a simulated clock. */
public class LibraryApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2026-09-01T10:00:00Z"));
        LibraryService library = new LibraryService(DailyFinePolicy.standard(), 3, clock);
        library.addListener(new LibraryListener() {
            @Override
            public void onHoldReady(Hold hold) {
                System.out.println("   [notice to " + hold.member().name() + "] '" + hold.book().title()
                        + "' is waiting for you until " + hold.pickUpBy());
            }

            @Override
            public void onHoldExpired(Hold hold) {
                System.out.println("   [notice to " + hold.member().name() + "] your hold " + hold.id() + " expired");
            }

            @Override
            public void onDueSoon(Loan loan) {
                System.out.println("   [notice to " + loan.member().name() + "] " + loan.copy().book().title() + " is due tomorrow");
            }

            @Override
            public void onOverdue(Loan loan, long daysLate) {
                System.out.println("   [notice to " + loan.member().name() + "] " + loan.copy().book().title() + " is " + daysLate + " day(s) late");
            }
        });

        library.addBook(new Book("978-0134685991", "Effective Java", List.of("Joshua Bloch"), "Programming", 2018, 4500));
        library.addBook(new Book("978-0132350884", "Clean Code", List.of("Robert C. Martin"), "Programming", 2008, 3800));
        library.addBook(new Book("978-0201633610", "Design Patterns", List.of("Erich Gamma", "Richard Helm", "Ralph Johnson", "John Vlissides"), "Software Design", 1994, 5000));
        library.addCopy("EJ-1", "978-0134685991", "A1");
        library.addCopy("CC-1", "978-0132350884", "A2");
        library.addCopy("CC-2", "978-0132350884", "A2");
        library.addCopy("DP-1", "978-0201633610", "B1");
        library.register("s1", "Priya", MemberType.STUDENT);
        library.register("s2", "Omar", MemberType.STUDENT);
        library.register("f1", "Dr. Chen", MemberType.FACULTY);

        step("Search");
        System.out.println("   'design' -> " + library.search("design"));
        System.out.println("   author 'gamma johnson' -> " + library.searchByAuthor("gamma johnson"));
        System.out.println("   'programming code' -> " + library.search("programming code"));

        step("Day 1: borrowing");
        attempt(() -> library.checkout("s1", "EJ-1"));
        attempt(() -> library.checkout("s1", "CC-1"));
        attempt(() -> library.checkout("s1", "CC-2"));
        attempt(() -> library.checkout("f1", "DP-1"));
        attempt(() -> library.checkout("s2", "EJ-1"));

        step("Omar joins the queue for Effective Java; Dr. Chen too");
        attempt(() -> library.placeHold("s2", "978-0134685991"));
        attempt(() -> library.placeHold("f1", "978-0134685991"));
        attempt(() -> library.placeHold("s2", "978-0132350884"));
        attempt(() -> library.renew("s1", "EJ-1"));

        step("Day 14: reminders");
        clock.advance(Duration.ofDays(13));
        library.sendNotices();
        attempt(() -> library.renew("s1", "CC-1"));

        step("Day 18: Priya returns Effective Java 3 days late");
        clock.advance(Duration.ofDays(4));
        library.sendNotices();
        attempt(() -> library.returnCopy("EJ-1"));
        attempt(() -> library.checkout("f1", "EJ-1"));

        step("Day 22: Omar never collected it");
        clock.advance(Duration.ofDays(4));
        library.expireHolds();
        attempt(() -> library.checkout("f1", "EJ-1"));

        step("Day 22: Priya lost Clean Code, is blocked, then settles up");
        attempt(() -> library.reportLost("CC-1"));
        System.out.printf("   Priya owes $%.2f%n", library.member("s1").finesDueCents() / 100.0);
        attempt(() -> library.checkout("s1", "CC-2"));
        library.payFine("s1", library.member("s1").finesDueCents());
        System.out.println("   paid in full");
        attempt(() -> library.placeHold("s1", "978-0201633610"));

        step("Day 30: state of the library");
        clock.advance(Duration.ofDays(8));
        System.out.println("   overdue: " + library.overdueLoans());
        System.out.println("   Clean Code copies on shelf: " + library.availableCopies("978-0132350884")
                + " (CC-2; CC-1 is lost)");
        System.out.println("   Design Patterns queue: " + library.holdQueue("978-0201633610"));
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (LibraryException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
