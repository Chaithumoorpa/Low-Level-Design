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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A small cinema's Friday, on a simulated clock (times are UTC). */
public class MovieBookingApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2027-09-10T09:00:00Z"));       // Friday morning
        FakePayments payments = new FakePayments();
        PricingStrategy pricing = PricingStrategy.peakAware(
                Map.of(SeatType.REGULAR, 10_00L, SeatType.PREMIUM, 14_00L, SeatType.RECLINER, 20_00L),
                12_500, 11_000, ZoneOffset.UTC);
        BookingService box = new BookingService(pricing, RefundPolicy.standard(), payments, true, ZoneOffset.UTC, clock);

        box.addCinema(new Cinema("c1", "Riverside Cinema", "Lisbon"));
        box.addScreen(new Screen("sc1", "c1", "Screen 1", List.of(
                new Screen.Row('A', 8, SeatType.REGULAR), new Screen.Row('B', 8, SeatType.REGULAR),
                new Screen.Row('C', 8, SeatType.PREMIUM), new Screen.Row('D', 4, SeatType.RECLINER))));
        box.addMovie(new Movie("m1", "The Long Orbit", 130, "EN"));
        box.addMovie(new Movie("m2", "Paper Lanterns", 95, "PT"));

        step("Scheduling (130 min film + 15 min cleaning)");
        Show matinee = box.scheduleShow("sc1", "m1", Instant.parse("2027-09-10T14:00:00Z"));
        attempt(() -> box.scheduleShow("sc1", "m2", Instant.parse("2027-09-10T16:20:00Z")));
        Show evening = box.scheduleShow("sc1", "m1", Instant.parse("2027-09-10T19:00:00Z"));
        System.out.println("   The Long Orbit in Lisbon today: " + box.findShows("m1", "lisbon", LocalDate.parse("2027-09-10")));

        step("Ana holds three premium seats for the evening show (evening price x1.25)");
        SeatHold ana = box.hold("ana", evening.id(), List.of("C3", "C4", "C5"));
        System.out.println("   " + ana);
        attempt(() -> box.hold("ben", evening.id(), List.of("C5", "C6")));
        attempt(() -> box.hold("ben", evening.id(), List.of("C7")));
        System.out.println("   (C7 would strand C6 between Ana's seats and Ben's)");
        attempt(() -> box.hold("ben", evening.id(), List.of("C6", "C7")));
        System.out.println("   (C6+C7 would strand C8 at the end of the row)");
        SeatHold ben = box.hold("ben", evening.id(), List.of("C6", "C7", "C8"));
        System.out.print(box.seatMap(evening.id()));

        step("Paying");
        payments.decline("ana");
        attempt(() -> box.confirm("ana", ana.id()));
        payments.allow("ana");
        Booking anaBooking = box.confirm("ana", ana.id());
        System.out.println("   " + anaBooking);
        System.out.println("   retry after a network error returns the same booking: " + box.confirm("ana", ana.id()).id());

        step("Ben walks away; his hold expires after 10 minutes");
        clock.advance(Duration.ofMinutes(10));
        attempt(() -> box.confirm("ben", ben.id()));
        System.out.print(box.seatMap(evening.id()));

        step("Cancellations follow the refund policy");
        Booking cara = box.confirm("cara", box.hold("cara", evening.id(), List.of("D1", "D2")).id());
        Booking dan = box.confirm("dan", box.hold("dan", matinee.id(), List.of("A1", "A2")).id());
        clock.advance(Duration.ofHours(3));                        // 12:10
        System.out.println("   " + box.cancel("cara", cara.id()) + "  (6h50 before: 50%)");
        clock.advance(Duration.ofHours(1).plusMinutes(55));       // 14:05
        attempt(() -> box.cancel("dan", dan.id()));
        System.out.println("   evening seats free: " + box.availableSeats(evening.id()) + ", payments net: "
                + String.format("$%.2f", payments.netCollected() / 100.0));
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (BookingException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
