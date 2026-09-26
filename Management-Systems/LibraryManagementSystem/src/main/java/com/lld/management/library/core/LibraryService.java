package com.lld.management.library.core;

import com.lld.management.library.catalog.Catalog;
import com.lld.management.library.model.Book;
import com.lld.management.library.model.BookCopy;
import com.lld.management.library.model.Hold;
import com.lld.management.library.model.LibraryException;
import com.lld.management.library.model.Loan;
import com.lld.management.library.model.Member;
import com.lld.management.library.model.MemberType;
import com.lld.management.library.policy.FinePolicy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Facade for the circulation desk: checkout, return, renew, holds, fines, lost items.
 *
 * <p>The key idea is {@link #route(BookCopy, List)}: whenever a copy comes free (returned, new, hold
 * not collected, hold cancelled) it goes to the <b>first member waiting</b> for that title, otherwise
 * back on the shelf. So "available copy" and "someone waiting" can never be true at the same time.
 *
 * <p>Concurrency: a library desk is low-traffic, so one lock guards all state. That keeps every rule
 * trivially atomic (two members scanning the last copy at the same moment: exactly one gets it).
 * Listener notices are sent after the lock is released.
 */
public final class LibraryService {

    private final Object lock = new Object();
    private final Catalog catalog = new Catalog();
    private final Map<String, Member> members = new HashMap<>();
    private final Map<String, BookCopy> copies = new HashMap<>();
    private final Map<String, List<BookCopy>> copiesByIsbn = new HashMap<>();
    private final Map<String, Loan> openLoanByBarcode = new HashMap<>();
    private final List<Loan> loans = new ArrayList<>();
    private final Map<String, Deque<Hold>> waitingByIsbn = new HashMap<>();
    private final Map<String, Hold> holds = new HashMap<>();
    private final Map<String, Hold> readyHoldByBarcode = new HashMap<>();
    private final Set<String> noticesSent = new HashSet<>();
    private final List<LibraryListener> listeners = new CopyOnWriteArrayList<>();
    private long loanSeq;
    private long holdSeq;

    private final FinePolicy fines;
    private final int holdPickupDays;
    private final Clock clock;

    public LibraryService(FinePolicy fines, int holdPickupDays, Clock clock) {
        this.fines = Objects.requireNonNull(fines);
        this.holdPickupDays = holdPickupDays;
        this.clock = Objects.requireNonNull(clock);
    }

    public void addListener(LibraryListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ setup

    public void addBook(Book book) {
        synchronized (lock) {
            catalog.add(book);
        }
    }

    public BookCopy addCopy(String barcode, String isbn, String rack) {
        return withEvents(events -> {
            Book book = catalog.byIsbn(isbn).orElseThrow(() -> new LibraryException("No book " + isbn));
            if (copies.containsKey(barcode)) {
                throw new LibraryException("Duplicate barcode " + barcode);
            }
            BookCopy copy = new BookCopy(barcode, book, rack);
            copies.put(barcode, copy);
            copiesByIsbn.computeIfAbsent(isbn, k -> new ArrayList<>()).add(copy);
            route(copy, events);                                // someone may already be waiting
            return copy;
        });
    }

    public Member register(String id, String name, MemberType type) {
        synchronized (lock) {
            if (members.containsKey(id)) {
                throw new LibraryException("Duplicate member " + id);
            }
            Member m = new Member(id, name, type);
            members.put(id, m);
            return m;
        }
    }

    public void setSuspended(String memberId, boolean suspended) {
        synchronized (lock) {
            member(memberId).setSuspended(suspended);
        }
    }

    // ------------------------------------------------------------------ circulation

    public Loan checkout(String memberId, String barcode) {
        return withEvents(events -> {
            Member member = member(memberId);
            BookCopy copy = copy(barcode);
            requireGoodStanding(member);
            List<Loan> open = openLoans(member);
            if (open.size() >= member.type().maxLoans()) {
                throw new LibraryException(member.name() + " already has " + open.size() + " loans (limit "
                        + member.type().maxLoans() + " for " + member.type() + ")");
            }
            if (open.stream().anyMatch(l -> l.copy().book().equals(copy.book()))) {
                throw new LibraryException(member.name() + " already has a copy of " + copy.book());
            }
            switch (copy.status()) {
                case AVAILABLE -> { }
                case ON_HOLD_SHELF -> {
                    Hold hold = readyHoldByBarcode.get(barcode);
                    if (!hold.member().equals(member)) {
                        throw new LibraryException(barcode + " is on the hold shelf for someone else");
                    }
                    readyHoldByBarcode.remove(barcode);
                    hold.finish(Hold.Status.FULFILLED);
                }
                case ON_LOAN -> throw new LibraryException(barcode + " is already on loan");
                case LOST -> throw new LibraryException(barcode + " is marked lost");
            }
            LocalDate today = today();
            Loan loan = new Loan("L" + (++loanSeq), copy, member, today, today.plusDays(member.type().loanDays()));
            copy.setStatus(BookCopy.Status.ON_LOAN);
            openLoanByBarcode.put(barcode, loan);
            loans.add(loan);
            return loan;
        });
    }

    /** Book dropped in the return slot: close the loan, charge any late fine, pass the copy on. */
    public Loan returnCopy(String barcode) {
        return withEvents(events -> {
            Loan loan = openLoan(barcode);
            long fine = fines.lateFine(loan, today());
            loan.member().charge(fine);
            loan.close(today(), fine);
            openLoanByBarcode.remove(barcode);
            route(loan.copy(), events);
            return loan;
        });
    }

    /** Extends the loan unless it is late, the renewal limit is reached, or someone is waiting. */
    public Loan renew(String memberId, String barcode) {
        return withEvents(events -> {
            Loan loan = openLoan(barcode);
            Member member = member(memberId);
            if (!loan.member().equals(member)) {
                throw new LibraryException(barcode + " is not on loan to " + member.name());
            }
            requireGoodStanding(member);
            if (loan.daysLate(today()) > 0) {
                throw new LibraryException(barcode + " is overdue; return it instead");
            }
            if (loan.renewals() >= member.type().maxRenewals()) {
                throw new LibraryException("Renewal limit (" + member.type().maxRenewals() + ") reached");
            }
            int waiting = waiting(loan.copy().book().isbn()).size();
            if (waiting > 0) {
                throw new LibraryException(waiting + " member(s) waiting for " + loan.copy().book());
            }
            LocalDate candidate = today().plusDays(member.type().loanDays());
            loan.renew(candidate.isAfter(loan.dueDate()) ? candidate : loan.dueDate());
            return loan;
        });
    }

    /** The member lost the book: charge replacement (+ any late fine) and retire the copy. */
    public Loan reportLost(String barcode) {
        return withEvents(events -> {
            Loan loan = openLoan(barcode);
            long fee = fines.lostFee(loan) + fines.lateFine(loan, today());
            loan.member().charge(fee);
            loan.close(today(), fee);
            openLoanByBarcode.remove(barcode);
            loan.copy().setStatus(BookCopy.Status.LOST);
            return loan;
        });
    }

    public void payFine(String memberId, long cents) {
        synchronized (lock) {
            Member m = member(memberId);
            if (cents <= 0 || cents > m.finesDueCents()) {
                throw new LibraryException("Payment must be between 1c and the " + m.finesDueCents() + "c owed");
            }
            m.pay(cents);
        }
    }

    // ------------------------------------------------------------------ holds

    /** Joins the queue for a title. Only when no copy is on the shelf: otherwise just borrow it. */
    public Hold placeHold(String memberId, String isbn) {
        return withEvents(events -> {
            Member member = member(memberId);
            Book book = catalog.byIsbn(isbn).orElseThrow(() -> new LibraryException("No book " + isbn));
            requireGoodStanding(member);
            List<BookCopy> owned = copiesByIsbn.getOrDefault(isbn, List.of()).stream()
                    .filter(c -> c.status() != BookCopy.Status.LOST).toList();
            if (owned.isEmpty()) {
                throw new LibraryException("The library has no copies of " + book);
            }
            long onShelf = owned.stream().filter(c -> c.status() == BookCopy.Status.AVAILABLE).count();
            if (onShelf > 0) {
                throw new LibraryException(onShelf + " copy(ies) of " + book + " on the shelf; borrow one directly");
            }
            if (openLoans(member).stream().anyMatch(l -> l.copy().book().equals(book))) {
                throw new LibraryException(member.name() + " already has " + book);
            }
            if (holds.values().stream().anyMatch(h -> h.isActive() && h.member().equals(member) && h.book().equals(book))) {
                throw new LibraryException(member.name() + " already has a hold on " + book);
            }
            Hold hold = new Hold("H" + (++holdSeq), member, book, today());
            holds.put(hold.id(), hold);
            waiting(isbn).addLast(hold);
            return hold;
        });
    }

    public void cancelHold(String holdId) {
        withEvents(events -> {
            Hold hold = hold(holdId);
            if (!hold.isActive()) {
                throw new LibraryException(holdId + " is " + hold.status());
            }
            if (hold.status() == Hold.Status.WAITING) {
                waiting(hold.book().isbn()).remove(hold);
                hold.finish(Hold.Status.CANCELLED);
            } else {
                readyHoldByBarcode.remove(hold.copy().barcode());
                hold.finish(Hold.Status.CANCELLED);
                route(hold.copy(), events);                     // next in line gets it
            }
            return null;
        });
    }

    /** Daily job: holds not collected by their pick-up date pass the copy to the next member. */
    public List<Hold> expireHolds() {
        return withEvents(events -> {
            LocalDate today = today();
            List<Hold> expired = readyHoldByBarcode.values().stream()
                    .filter(h -> today.isAfter(h.pickUpBy()))
                    .sorted(Comparator.comparing(Hold::id)).toList();
            for (Hold h : expired) {
                readyHoldByBarcode.remove(h.copy().barcode());
                h.finish(Hold.Status.EXPIRED);
                events.add(() -> listeners.forEach(l -> l.onHoldExpired(h)));
                route(h.copy(), events);
            }
            return expired;
        });
    }

    /** Daily job: due-tomorrow and overdue notices, at most one per loan per day. */
    public int sendNotices() {
        return withEvents(events -> {
            LocalDate today = today();
            int sent = 0;
            for (Loan loan : openLoanByBarcode.values()) {
                long late = loan.daysLate(today);
                if (late > 0 && noticesSent.add(loan.id() + "|late|" + today)) {
                    events.add(() -> listeners.forEach(l -> l.onOverdue(loan, late)));
                    sent++;
                } else if (loan.dueDate().equals(today.plusDays(1)) && noticesSent.add(loan.id() + "|soon")) {
                    events.add(() -> listeners.forEach(l -> l.onDueSoon(loan)));
                    sent++;
                }
            }
            return sent;
        });
    }

    // ------------------------------------------------------------------ queries

    public List<Book> search(String query) {
        synchronized (lock) {
            return catalog.searchAll(query);
        }
    }

    public List<Book> searchByAuthor(String query) {
        synchronized (lock) {
            return catalog.searchAuthor(query);
        }
    }

    public List<Book> searchByTitle(String query) {
        synchronized (lock) {
            return catalog.searchTitle(query);
        }
    }

    public List<Book> searchBySubject(String query) {
        synchronized (lock) {
            return catalog.searchSubject(query);
        }
    }

    public long availableCopies(String isbn) {
        synchronized (lock) {
            return copiesByIsbn.getOrDefault(isbn, List.of()).stream()
                    .filter(c -> c.status() == BookCopy.Status.AVAILABLE).count();
        }
    }

    public List<Loan> loansOf(String memberId) {
        synchronized (lock) {
            return openLoans(member(memberId));
        }
    }

    public List<Loan> overdueLoans() {
        synchronized (lock) {
            LocalDate today = today();
            return openLoanByBarcode.values().stream().filter(l -> l.daysLate(today) > 0)
                    .sorted(Comparator.comparing(Loan::dueDate).thenComparing(Loan::id)).toList();
        }
    }

    public List<Hold> holdQueue(String isbn) {
        synchronized (lock) {
            return List.copyOf(waiting(isbn));
        }
    }

    public Member member(String memberId) {
        synchronized (lock) {
            Member m = members.get(memberId);
            if (m == null) {
                throw new LibraryException("No member " + memberId);
            }
            return m;
        }
    }

    public BookCopy copy(String barcode) {
        synchronized (lock) {
            BookCopy c = copies.get(barcode);
            if (c == null) {
                throw new LibraryException("No copy " + barcode);
            }
            return c;
        }
    }

    public Hold hold(String holdId) {
        synchronized (lock) {
            Hold h = holds.get(holdId);
            if (h == null) {
                throw new LibraryException("No hold " + holdId);
            }
            return h;
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ------------------------------------------------------------------ internals

    /** A copy just came free: first waiting member gets it on the hold shelf, else it goes back on the shelf. */
    private void route(BookCopy copy, List<Runnable> events) {
        Hold next = waiting(copy.book().isbn()).pollFirst();
        if (next == null) {
            copy.setStatus(BookCopy.Status.AVAILABLE);
            return;
        }
        next.ready(copy, today().plusDays(holdPickupDays));
        copy.setStatus(BookCopy.Status.ON_HOLD_SHELF);
        readyHoldByBarcode.put(copy.barcode(), next);
        events.add(() -> listeners.forEach(l -> l.onHoldReady(next)));
    }

    private void requireGoodStanding(Member m) {
        if (m.suspended()) {
            throw new LibraryException(m.name() + "'s card is suspended");
        }
        if (m.finesDueCents() >= fines.blockingThresholdCents()) {
            throw new LibraryException(String.format("%s owes $%.2f; pay fines first", m.name(), m.finesDueCents() / 100.0));
        }
        LocalDate today = today();
        if (openLoans(m).stream().anyMatch(l -> l.daysLate(today) > 0)) {
            throw new LibraryException(m.name() + " has overdue books; return them first");
        }
    }

    private List<Loan> openLoans(Member m) {
        return openLoanByBarcode.values().stream().filter(l -> l.member().equals(m))
                .sorted(Comparator.comparing(Loan::id)).toList();
    }

    private Loan openLoan(String barcode) {
        copy(barcode);
        Loan loan = openLoanByBarcode.get(barcode);
        if (loan == null) {
            throw new LibraryException(barcode + " is not on loan");
        }
        return loan;
    }

    private Deque<Hold> waiting(String isbn) {
        return waitingByIsbn.computeIfAbsent(isbn, k -> new ArrayDeque<>());
    }

    private interface Action<T> {
        T run(List<Runnable> events);
    }

    /** Runs {@code action} under the lock, then publishes the events it queued. */
    private <T> T withEvents(Action<T> action) {
        List<Runnable> events = new ArrayList<>();
        T result;
        synchronized (lock) {
            result = action.run(events);
        }
        events.forEach(Runnable::run);
        return result;
    }
}
