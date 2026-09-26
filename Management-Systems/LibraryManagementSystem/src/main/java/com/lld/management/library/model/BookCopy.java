package com.lld.management.library.model;

/**
 * One physical item with its own barcode and shelf location.
 *
 * <pre>
 * AVAILABLE --checkout--> ON_LOAN --return--> AVAILABLE, or ON_HOLD_SHELF if someone is waiting
 * ON_HOLD_SHELF --checkout by that member--> ON_LOAN
 * ON_HOLD_SHELF --not collected in time--> next in queue, or AVAILABLE
 * ON_LOAN --reported lost--> LOST
 * </pre>
 */
public final class BookCopy {

    public enum Status {
        AVAILABLE, ON_LOAN, ON_HOLD_SHELF, LOST
    }

    private final String barcode;
    private final Book book;
    private final String rack;
    private Status status = Status.AVAILABLE;

    public BookCopy(String barcode, Book book, String rack) {
        this.barcode = barcode;
        this.book = book;
        this.rack = rack;
    }

    public String barcode() {
        return barcode;
    }

    public Book book() {
        return book;
    }

    public String rack() {
        return rack;
    }

    public Status status() {
        return status;
    }

    /** Changed only by the library service, which checks the transition first. */
    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return barcode + " " + book.title() + " [" + status + "]";
    }
}
