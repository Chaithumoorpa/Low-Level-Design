package com.lld.management.library.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** One borrowing of one copy by one member, from checkout until return (or loss). */
public final class Loan {

    private final String id;
    private final BookCopy copy;
    private final Member member;
    private final LocalDate borrowedOn;
    private LocalDate dueDate;
    private int renewals;
    private LocalDate closedOn;
    private long fineCents;

    public Loan(String id, BookCopy copy, Member member, LocalDate borrowedOn, LocalDate dueDate) {
        this.id = id;
        this.copy = copy;
        this.member = member;
        this.borrowedOn = borrowedOn;
        this.dueDate = dueDate;
    }

    public String id() {
        return id;
    }

    public BookCopy copy() {
        return copy;
    }

    public Member member() {
        return member;
    }

    public LocalDate borrowedOn() {
        return borrowedOn;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public int renewals() {
        return renewals;
    }

    public LocalDate closedOn() {
        return closedOn;
    }

    public long fineCents() {
        return fineCents;
    }

    public boolean isOpen() {
        return closedOn == null;
    }

    /** Whole days past the due date on {@code day} (0 if not late). */
    public long daysLate(LocalDate day) {
        return Math.max(0, ChronoUnit.DAYS.between(dueDate, day));
    }

    public void renew(LocalDate newDueDate) {
        dueDate = newDueDate;
        renewals++;
    }

    public void close(LocalDate on, long fine) {
        closedOn = on;
        fineCents = fine;
    }

    @Override
    public String toString() {
        return id + " " + copy.barcode() + " '" + copy.book().title() + "' to " + member.name() + ", due " + dueDate
                + (isOpen() ? "" : ", closed " + closedOn + (fineCents > 0 ? String.format(", fine $%.2f", fineCents / 100.0) : ""));
    }
}
