package com.lld.management.library.model;

import java.time.LocalDate;

/**
 * A place in the queue for a title (any copy). When a copy comes back it is set aside for the first
 * waiting member, who has a few days to collect it.
 */
public final class Hold {

    public enum Status {
        WAITING, READY, FULFILLED, EXPIRED, CANCELLED
    }

    private final String id;
    private final Member member;
    private final Book book;
    private final LocalDate placedOn;
    private Status status = Status.WAITING;
    private BookCopy copy;
    private LocalDate pickUpBy;

    public Hold(String id, Member member, Book book, LocalDate placedOn) {
        this.id = id;
        this.member = member;
        this.book = book;
        this.placedOn = placedOn;
    }

    public String id() {
        return id;
    }

    public Member member() {
        return member;
    }

    public Book book() {
        return book;
    }

    public LocalDate placedOn() {
        return placedOn;
    }

    public Status status() {
        return status;
    }

    /** The copy waiting on the hold shelf (only when READY). */
    public BookCopy copy() {
        return copy;
    }

    public LocalDate pickUpBy() {
        return pickUpBy;
    }

    public void ready(BookCopy copy, LocalDate pickUpBy) {
        this.copy = copy;
        this.pickUpBy = pickUpBy;
        this.status = Status.READY;
    }

    public void finish(Status status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == Status.WAITING || status == Status.READY;
    }

    @Override
    public String toString() {
        return id + " " + member.name() + " for '" + book.title() + "' [" + status + "]"
                + (status == Status.READY ? " copy " + copy.barcode() + " until " + pickUpBy : "");
    }
}
