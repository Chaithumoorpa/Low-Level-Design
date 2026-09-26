package com.lld.booking.movie.service;

import com.lld.booking.movie.model.Booking;
import com.lld.booking.movie.model.BookingException;
import com.lld.booking.movie.model.Cinema;
import com.lld.booking.movie.model.Movie;
import com.lld.booking.movie.model.Screen;
import com.lld.booking.movie.model.Seat;
import com.lld.booking.movie.model.SeatHold;
import com.lld.booking.movie.model.Show;
import com.lld.booking.movie.model.Show.SeatStatus;
import com.lld.booking.movie.payment.PaymentPort;
import com.lld.booking.movie.pricing.PricingStrategy;
import com.lld.booking.movie.pricing.RefundPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Facade: catalogue, show scheduling, seat holds, payment, cancellation.
 *
 * <p><b>Two-phase booking.</b> Choosing seats creates a short {@link SeatHold} (nobody else can take them);
 * paying turns the hold into a {@link Booking}. Abandoned holds expire and the seats go back on sale.
 *
 * <p><b>Concurrency.</b> Everything touching a show's seats runs under that show's monitor, so two people
 * clicking the same seat get one success and one "already taken". The payment call happens
 * <em>outside</em> the lock: the hold is first marked PAYING (so it can't expire meanwhile), then
 * finalised under the lock again.
 */
public final class BookingService {

    public static final int MAX_SEATS = 10;
    public static final Duration HOLD_TIME = Duration.ofMinutes(10);
    public static final Duration CLEANING_GAP = Duration.ofMinutes(15);
    public static final long FEE_PER_TICKET = 50;                    // convenience fee, cents

    private final Map<String, Cinema> cinemas = new ConcurrentHashMap<>();
    private final Map<String, Screen> screens = new ConcurrentHashMap<>();
    private final Map<String, Movie> movies = new ConcurrentHashMap<>();
    private final Map<String, Show> shows = new ConcurrentHashMap<>();
    private final Map<String, SeatHold> holds = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookingByHold = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private final PricingStrategy pricing;
    private final RefundPolicy refunds;
    private final PaymentPort payments;
    private final boolean noLoneSeats;
    private final ZoneId zone;
    private final Clock clock;

    public BookingService(PricingStrategy pricing, RefundPolicy refunds, PaymentPort payments, boolean noLoneSeats,
                          ZoneId zone, Clock clock) {
        this.pricing = Objects.requireNonNull(pricing);
        this.refunds = Objects.requireNonNull(refunds);
        this.payments = Objects.requireNonNull(payments);
        this.noLoneSeats = noLoneSeats;
        this.zone = zone;
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ catalogue

    public void addCinema(Cinema c) {
        cinemas.put(c.id(), c);
    }

    public void addScreen(Screen s) {
        if (!cinemas.containsKey(s.cinemaId())) {
            throw new BookingException("No cinema " + s.cinemaId());
        }
        screens.put(s.id(), s);
    }

    public void addMovie(Movie m) {
        movies.put(m.id(), m);
    }

    /** A screen can't run two shows at once; a cleaning gap is kept between them. */
    public synchronized Show scheduleShow(String screenId, String movieId, Instant startsAt) {
        Screen screen = get(screens, screenId, "screen");
        Movie movie = get(movies, movieId, "movie");
        Instant endsAt = startsAt.plus(Duration.ofMinutes(movie.minutes()));
        for (Show other : shows.values()) {
            if (other.screen().id().equals(screenId)
                    && startsAt.isBefore(other.endsAt().plus(CLEANING_GAP))
                    && other.startsAt().isBefore(endsAt.plus(CLEANING_GAP))) {
                throw new BookingException(screen.name() + " is busy with " + other.movie().title() + " until "
                        + other.endsAt().plus(CLEANING_GAP));
            }
        }
        Show show = new Show("S" + seq.incrementAndGet(), movie, screen, startsAt);
        shows.put(show.id(), show);
        return show;
    }

    /** Upcoming shows of a movie in a city on a local date, earliest first. */
    public List<Show> findShows(String movieId, String city, LocalDate date) {
        return shows.values().stream()
                .filter(s -> s.movie().id().equals(movieId))
                .filter(s -> cinemas.get(s.screen().cinemaId()).city().equalsIgnoreCase(city))
                .filter(s -> s.startsAt().atZone(zone).toLocalDate().equals(date))
                .filter(s -> s.startsAt().isAfter(now()))
                .sorted(Comparator.comparing(Show::startsAt).thenComparing(Show::id))
                .toList();
    }

    public long availableSeats(String showId) {
        Show show = get(shows, showId, "show");
        synchronized (show) {
            expireHoldsOf(show);
            return show.count(SeatStatus.AVAILABLE);
        }
    }

    public String seatMap(String showId) {
        Show show = get(shows, showId, "show");
        synchronized (show) {
            expireHoldsOf(show);
            return show.seatMap();
        }
    }

    // ------------------------------------------------------------------ hold → pay → book

    /** Reserves seats for {@link #HOLD_TIME}. A user's previous live hold on the same show is released. */
    public SeatHold hold(String userId, String showId, List<String> seatIds) {
        Show show = get(shows, showId, "show");
        synchronized (show) {
            expireHoldsOf(show);
            if (!now().isBefore(show.startsAt())) {
                throw new BookingException("The show has already started");
            }
            Set<String> wanted = new LinkedHashSet<>(seatIds);
            if (wanted.isEmpty() || wanted.size() > MAX_SEATS || wanted.size() != seatIds.size()) {
                throw new BookingException("Choose 1 to " + MAX_SEATS + " different seats");
            }
            holds.values().stream()
                    .filter(h -> h.userId().equals(userId) && h.showId().equals(showId) && h.status() == SeatHold.Status.ACTIVE)
                    .toList().forEach(h -> release(show, h, SeatHold.Status.RELEASED));
            long price = 0;
            for (String id : wanted) {
                Seat seat = show.screen().seat(id).orElseThrow(() -> new BookingException("No seat " + id));
                if (show.status(id) != SeatStatus.AVAILABLE) {
                    throw new BookingException("Seat " + id + " is already taken");
                }
                price += pricing.price(show, seat) + FEE_PER_TICKET;
            }
            if (noLoneSeats) {
                String lone = loneSeatLeft(show, wanted);
                if (lone != null) {
                    throw new BookingException("That would leave seat " + lone + " stranded on its own; shift your selection");
                }
            }
            SeatHold hold = new SeatHold("H" + seq.incrementAndGet(), userId, showId, List.copyOf(wanted), price,
                    now().plus(HOLD_TIME));
            wanted.forEach(id -> show.set(id, SeatStatus.HELD, hold.id()));
            holds.put(hold.id(), hold);
            return hold;
        }
    }

    /**
     * Pays for a hold. Safe to retry: a hold that was already confirmed returns its booking. If the card is
     * declined the hold stays ACTIVE (until it expires) so the customer can try another card.
     */
    public Booking confirm(String userId, String holdId) {
        SeatHold hold = get(holds, holdId, "hold");
        Show show = shows.get(hold.showId());
        synchronized (show) {
            if (!hold.userId().equals(userId)) {
                throw new BookingException("Not your hold");
            }
            Booking done = bookingByHold.get(holdId);
            if (done != null) {
                return done;                                          // idempotent retry
            }
            expireHoldsOf(show);
            if (hold.status() != SeatHold.Status.ACTIVE) {
                throw new BookingException("Hold " + holdId + " is " + hold.status() + "; please select seats again");
            }
            hold.setStatus(SeatHold.Status.PAYING);
        }
        String reference;
        try {
            reference = payments.charge(userId, hold.priceCents(), show.movie().title() + " " + hold.seatIds());
        } catch (PaymentPort.Declined e) {
            synchronized (show) {
                hold.setStatus(SeatHold.Status.ACTIVE);
            }
            throw new BookingException("Payment declined; your seats stay held until " + hold.expiresAt());
        }
        synchronized (show) {
            Booking b = new Booking("B" + seq.incrementAndGet(), userId, show.id(), hold.seatIds(), hold.priceCents(),
                    reference, now());
            hold.setStatus(SeatHold.Status.CONFIRMED);
            hold.seatIds().forEach(id -> show.set(id, SeatStatus.BOOKED, b.id()));
            bookings.put(b.id(), b);
            bookingByHold.put(holdId, b);
            return b;
        }
    }

    public void releaseHold(String userId, String holdId) {
        SeatHold hold = get(holds, holdId, "hold");
        Show show = shows.get(hold.showId());
        synchronized (show) {
            if (!hold.userId().equals(userId) || hold.status() != SeatHold.Status.ACTIVE) {
                throw new BookingException("No active hold " + holdId + " for " + userId);
            }
            release(show, hold, SeatHold.Status.RELEASED);
        }
    }

    /** Scheduler job (also done lazily on every seat operation). @return holds expired */
    public int expireHolds() {
        int n = 0;
        for (Show show : shows.values()) {
            synchronized (show) {
                n += expireHoldsOf(show);
            }
        }
        return n;
    }

    /** Refund per policy; seats go back on sale. Not possible once the show has started. */
    public Booking cancel(String userId, String bookingId) {
        Booking b = get(bookings, bookingId, "booking");
        Show show = shows.get(b.showId());
        long refund;
        synchronized (show) {
            if (!b.userId().equals(userId) || b.status() != Booking.Status.CONFIRMED) {
                throw new BookingException("No confirmed booking " + bookingId + " for " + userId);
            }
            Duration before = Duration.between(now(), show.startsAt());
            if (before.isNegative() || before.isZero()) {
                throw new BookingException("The show has started; tickets can't be cancelled");
            }
            refund = b.amountCents() * refunds.refundPercent(before) / 100;
            b.cancel(refund);
            b.seatIds().forEach(id -> show.set(id, SeatStatus.AVAILABLE, null));
        }
        if (refund > 0) {
            payments.refund(b.paymentReference(), refund);
        }
        return b;
    }

    public List<Booking> bookingsOf(String userId) {
        return bookings.values().stream().filter(b -> b.userId().equals(userId))
                .sorted(Comparator.comparing(Booking::id)).toList();
    }

    public Show show(String id) {
        return get(shows, id, "show");
    }

    // ------------------------------------------------------------------ internals (show lock held)

    private int expireHoldsOf(Show show) {
        int n = 0;
        for (SeatHold h : holds.values()) {
            if (h.showId().equals(show.id()) && h.status() == SeatHold.Status.ACTIVE && !now().isBefore(h.expiresAt())) {
                release(show, h, SeatHold.Status.EXPIRED);
                n++;
            }
        }
        return n;
    }

    private static void release(Show show, SeatHold h, SeatHold.Status to) {
        h.seatIds().forEach(id -> show.set(id, SeatStatus.AVAILABLE, null));
        h.setStatus(to);
    }

    /**
     * Would this selection leave a single free seat squeezed between the selection and another taken
     * seat or the row's end? (A common cinema rule: such seats rarely sell.) Only seats next to the new
     * selection are checked, so existing gaps don't block anyone.
     */
    private static String loneSeatLeft(Show show, Set<String> wanted) {
        Set<String> checked = new HashSet<>();
        for (List<Seat> row : show.screen().rows().values()) {
            for (int i = 0; i < row.size(); i++) {
                if (!wanted.contains(row.get(i).id())) {
                    continue;
                }
                for (int j : new int[]{i - 1, i + 1}) {
                    if (j < 0 || j >= row.size()) {
                        continue;
                    }
                    Seat s = row.get(j);
                    if (wanted.contains(s.id()) || show.status(s.id()) != SeatStatus.AVAILABLE || !checked.add(s.id())) {
                        continue;
                    }
                    boolean leftBlocked = j == 0 || taken(show, wanted, row.get(j - 1));
                    boolean rightBlocked = j == row.size() - 1 || taken(show, wanted, row.get(j + 1));
                    if (leftBlocked && rightBlocked) {
                        return s.id();
                    }
                }
            }
        }
        return null;
    }

    private static boolean taken(Show show, Set<String> wanted, Seat s) {
        return wanted.contains(s.id()) || show.status(s.id()) != SeatStatus.AVAILABLE;
    }

    private static <T> T get(Map<String, T> map, String id, String what) {
        T t = map.get(id);
        if (t == null) {
            throw new BookingException("No " + what + " " + id);
        }
        return t;
    }

    private Instant now() {
        return clock.instant();
    }

    List<SeatHold> holdsSnapshot() {
        return new ArrayList<>(holds.values());
    }
}
