package com.lld.booking.movie;

import com.lld.booking.movie.model.Booking;
import com.lld.booking.movie.model.BookingException;
import com.lld.booking.movie.model.Cinema;
import com.lld.booking.movie.model.Movie;
import com.lld.booking.movie.model.Screen;
import com.lld.booking.movie.model.SeatHold;
import com.lld.booking.movie.model.SeatType;
import com.lld.booking.movie.model.Show;
import com.lld.booking.movie.payment.FakePayments;
import com.lld.booking.movie.pricing.PricingStrategy;
import com.lld.booking.movie.pricing.RefundPolicy;
import com.lld.booking.movie.service.BookingService;
import com.lld.booking.movie.service.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingServiceTest {

    private ManualClock clock;
    private FakePayments payments;
    private BookingService box;
    private Show show;                                             // Friday 19:00, evening price x1.25

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2027-09-10T09:00:00Z"));
        payments = new FakePayments();
        box = service(true);
        show = box.scheduleShow("sc1", "m1", Instant.parse("2027-09-10T19:00:00Z"));
    }

    private BookingService service(boolean noLoneSeats) {
        BookingService b = new BookingService(PricingStrategy.peakAware(
                Map.of(SeatType.REGULAR, 10_00L, SeatType.PREMIUM, 14_00L, SeatType.RECLINER, 20_00L), 12_500, 11_000,
                ZoneOffset.UTC), RefundPolicy.standard(), payments, noLoneSeats, ZoneOffset.UTC, clock);
        b.addCinema(new Cinema("c1", "Riverside", "Lisbon"));
        b.addCinema(new Cinema("c2", "Harbour", "Porto"));
        b.addScreen(new Screen("sc1", "c1", "Screen 1", List.of(
                new Screen.Row('A', 8, SeatType.REGULAR), new Screen.Row('C', 8, SeatType.PREMIUM),
                new Screen.Row('D', 4, SeatType.RECLINER))));
        b.addScreen(new Screen("sc2", "c2", "Main", List.of(new Screen.Row('A', 5, SeatType.REGULAR))));
        b.addMovie(new Movie("m1", "The Long Orbit", 130, "EN"));
        b.addMovie(new Movie("m2", "Paper Lanterns", 95, "PT"));
        return b;
    }

    // ------------------------------------------------------------------ scheduling & pricing

    @Nested
    class Scheduling {

        @Test
        void overlapIncludesTheCleaningGap() {
            assertThrows(BookingException.class, () -> box.scheduleShow("sc1", "m2", Instant.parse("2027-09-10T21:24:00Z")));
            box.scheduleShow("sc1", "m2", Instant.parse("2027-09-10T21:25:00Z"));      // 19:00 + 130 + 15
            assertThrows(BookingException.class, () -> box.scheduleShow("sc1", "m2", Instant.parse("2027-09-10T17:30:00Z")),
                    "17:30 + 95 min + 15 runs into 19:00");
            box.scheduleShow("sc1", "m2", Instant.parse("2027-09-10T17:10:00Z"));
            box.scheduleShow("sc2", "m1", Instant.parse("2027-09-10T19:00:00Z"));      // other screen: fine
        }

        @Test
        void findShowsByCityAndDateOnlyFuture() {
            Show porto = box.scheduleShow("sc2", "m1", Instant.parse("2027-09-10T20:00:00Z"));
            box.scheduleShow("sc1", "m1", Instant.parse("2027-09-11T19:00:00Z"));
            assertEquals(List.of(show), box.findShows("m1", "LISBON", LocalDate.parse("2027-09-10")));
            assertEquals(List.of(porto), box.findShows("m1", "Porto", LocalDate.parse("2027-09-10")));
            clock.advance(Duration.ofHours(10));
            assertEquals(List.of(), box.findShows("m1", "Lisbon", LocalDate.parse("2027-09-10")), "already started");
        }

        @ParameterizedTest(name = "{0} {1} -> {2}c")
        @CsvSource({"2027-09-10T14:00:00Z,A1,1000", "2027-09-10T19:00:00Z,A1,1250", "2027-09-11T14:00:00Z,A1,1100",
                "2027-09-11T19:00:00Z,A1,1375", "2027-09-10T18:00:00Z,D1,2500", "2027-09-10T17:59:00Z,C1,1400"})
        void peakPricing(String start, String seat, long cents) {
            Show s = new Show("x", new Movie("m", "m", 90, "EN"), new Screen("s", "c1", "s", List.of(
                    new Screen.Row('A', 1, SeatType.REGULAR), new Screen.Row('C', 1, SeatType.PREMIUM),
                    new Screen.Row('D', 1, SeatType.RECLINER))), Instant.parse(start));
            PricingStrategy p = PricingStrategy.peakAware(Map.of(SeatType.REGULAR, 10_00L, SeatType.PREMIUM, 14_00L,
                    SeatType.RECLINER, 20_00L), 12_500, 11_000, ZoneOffset.UTC);
            assertEquals(cents, p.price(s, s.screen().seat(seat).orElseThrow()));
        }
    }

    // ------------------------------------------------------------------ holds

    @Nested
    class Holds {

        @Test
        void heldSeatsCantBeTakenAndPriceIncludesFees() {
            SeatHold h = box.hold("ana", show.id(), List.of("C3", "C4"));
            assertEquals(2 * (1750 + 50), h.priceCents());
            assertThrows(BookingException.class, () -> box.hold("ben", show.id(), List.of("C4")));
            assertEquals(18, box.availableSeats(show.id()));
        }

        @Test
        void validation() {
            assertThrows(BookingException.class, () -> box.hold("a", show.id(), List.of()));
            assertThrows(BookingException.class, () -> box.hold("a", show.id(), List.of("A1", "A1")));
            assertThrows(BookingException.class, () -> box.hold("a", show.id(), List.of("Z9")));
            List<String> eleven = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                eleven.add("A" + i);
            }
            eleven.addAll(List.of("D1", "D2", "D3"));
            assertThrows(BookingException.class, () -> box.hold("a", show.id(), eleven), "max 10");
            clock.advance(Duration.ofHours(10));
            assertThrows(BookingException.class, () -> box.hold("a", show.id(), List.of("A1")), "show started");
        }

        @Test
        void aNewHoldReplacesTheUsersPreviousOne() {
            SeatHold first = box.hold("ana", show.id(), List.of("A1", "A2"));
            box.hold("ana", show.id(), List.of("A5", "A6"));
            assertEquals(SeatHold.Status.RELEASED, first.status());
            box.hold("ben", show.id(), List.of("A1", "A2"));
        }

        @Test
        void holdsExpireAndReleaseCanBeExplicit() {
            SeatHold h = box.hold("ana", show.id(), List.of("A1", "A2"));
            clock.advance(Duration.ofMinutes(9));
            assertEquals(18, box.availableSeats(show.id()));
            clock.advance(Duration.ofMinutes(1));
            assertEquals(1, box.expireHolds());
            assertEquals(SeatHold.Status.EXPIRED, h.status());
            assertThrows(BookingException.class, () -> box.confirm("ana", h.id()));
            SeatHold again = box.hold("ben", show.id(), List.of("A1", "A2"));
            box.releaseHold("ben", again.id());
            assertEquals(20, box.availableSeats(show.id()));
            assertThrows(BookingException.class, () -> box.releaseHold("ben", again.id()));
        }

        @Test
        void loneSeatRuleCanBeTurnedOff() {
            box.hold("ana", show.id(), List.of("A1", "A2"));
            assertThrows(BookingException.class, () -> box.hold("ben", show.id(), List.of("A4", "A5")), "strands A3");
            assertThrows(BookingException.class, () -> box.hold("ben", show.id(), List.of("A7")), "strands A8 at the edge");
            box.hold("ben", show.id(), List.of("A3", "A4"));
            assertThrows(BookingException.class, () -> box.hold("cara", show.id(), List.of("A6", "A7", "A8")), "strands A5");
            box.hold("cara", show.id(), List.of("A5", "A6", "A7", "A8"));
            box.hold("dan", show.id(), List.of("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8"));
            box.hold("eve", show.id(), List.of("D1", "D2", "D3", "D4"));
            assertEquals(0, box.availableSeats(show.id()), "a full house with no stranded seats");

            BookingService relaxed = service(false);
            Show s = relaxed.scheduleShow("sc1", "m1", Instant.parse("2027-09-10T19:00:00Z"));
            relaxed.hold("ana", s.id(), List.of("A1", "A2"));
            relaxed.hold("ben", s.id(), List.of("A4", "A5"));
        }
    }

    // ------------------------------------------------------------------ paying & cancelling

    @Nested
    class Paying {

        @Test
        void confirmIsIdempotentAndOwnerOnly() {
            SeatHold h = box.hold("ana", show.id(), List.of("D1", "D2"));
            assertThrows(BookingException.class, () -> box.confirm("ben", h.id()));
            Booking b = box.confirm("ana", h.id());
            assertEquals(b, box.confirm("ana", h.id()));
            assertEquals(1, payments.charges());
            assertEquals(Show.SeatStatus.BOOKED, show.status("D1"));
            assertEquals(List.of(b), box.bookingsOf("ana"));
        }

        @Test
        void declinedPaymentKeepsTheHold() {
            SeatHold h = box.hold("ana", show.id(), List.of("A1", "A2"));
            payments.decline("ana");
            assertThrows(BookingException.class, () -> box.confirm("ana", h.id()));
            assertEquals(SeatHold.Status.ACTIVE, h.status());
            assertEquals(Show.SeatStatus.HELD, show.status("A1"));
            payments.allow("ana");
            box.confirm("ana", h.id());
        }

        @ParameterizedTest(name = "cancel {0}h before -> {1}%")
        @CsvSource({"30,100", "24,100", "23,50", "2,50", "1,0"})
        void refundPolicy(long hoursBefore, int percent) {
            Show later = box.scheduleShow("sc2", "m1", Instant.parse("2027-09-12T12:00:00Z"));
            Booking b = box.confirm("ana", box.hold("ana", later.id(), List.of("A1", "A2")).id());
            clock.advance(Duration.between(clock.instant(), later.startsAt().minus(Duration.ofHours(hoursBefore))));
            Booking cancelled = box.cancel("ana", b.id());
            assertEquals(b.amountCents() * percent / 100, cancelled.refundedCents());
            assertEquals(b.amountCents() - cancelled.refundedCents(), payments.netCollected());
            assertEquals(Show.SeatStatus.AVAILABLE, later.status("A1"));
        }

        @Test
        void noCancellingAfterTheStartOrTwice() {
            Booking b = box.confirm("ana", box.hold("ana", show.id(), List.of("A1", "A2")).id());
            assertThrows(BookingException.class, () -> box.cancel("ben", b.id()));
            box.cancel("ana", b.id());
            assertThrows(BookingException.class, () -> box.cancel("ana", b.id()));
            Booking c = box.confirm("cara", box.hold("cara", show.id(), List.of("C1", "C2")).id());
            clock.advance(Duration.ofHours(10));
            assertThrows(BookingException.class, () -> box.cancel("cara", c.id()));
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void manyUsersRaceForTheSameSeats() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger booked = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String user = "u" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    box.confirm(user, box.hold(user, show.id(), List.of("D1", "D2")).id());
                    booked.incrementAndGet();
                } catch (BookingException taken) {
                    // someone else was faster
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, booked.get());
        assertEquals(1, payments.charges(), "nobody paid for seats they didn't get");
        assertTrue(show.status("D1") == Show.SeatStatus.BOOKED);
    }
}
