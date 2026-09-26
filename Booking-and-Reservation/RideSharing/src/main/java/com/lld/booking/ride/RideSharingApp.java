package com.lld.booking.ride;

import com.lld.booking.ride.model.FareQuote;
import com.lld.booking.ride.model.Location;
import com.lld.booking.ride.model.RideException;
import com.lld.booking.ride.model.Trip;
import com.lld.booking.ride.model.VehicleType;
import com.lld.booking.ride.pricing.FareCalculator;
import com.lld.booking.ride.pricing.SurgePolicy;
import com.lld.booking.ride.service.FakePayments;
import com.lld.booking.ride.service.ManualClock;
import com.lld.booking.ride.service.RideService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** An evening of rides in a small city grid (km), on a simulated clock. */
public class RideSharingApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2027-11-05T18:00:00Z"));
        FakePayments payments = new FakePayments();
        RideService uber = new RideService(new FareCalculator(), SurgePolicy.standard(), payments, clock);
        uber.addDriver("d1", "Dmitri", VehicleType.ECONOMY, new Location(1, 1));
        uber.addDriver("d2", "Esi", VehicleType.ECONOMY, new Location(2, 0));
        uber.addDriver("d3", "Farah", VehicleType.PREMIUM, new Location(0, 2));
        uber.addDriver("d4", "Goran", VehicleType.ECONOMY, new Location(9, 9));
        for (String d : List.of("d1", "d2", "d3", "d4")) {
            uber.goOnline(d, uber.driver(d).location());
        }
        uber.addRider("ana", "Ana");
        uber.addRider("ben", "Ben");
        Location home = new Location(0, 0);
        Location office = new Location(6, 8);

        step("Ana gets a quote and requests; the nearest economy driver gets the offer");
        FareQuote q = uber.quote("ana", home, office, VehicleType.ECONOMY);
        System.out.println("   " + q);
        Trip trip = uber.request("ana", q.id());
        System.out.println("   offered to " + trip.offeredTo() + " (nearest ECONOMY car; Farah drives PREMIUM)");

        step("Dmitri doesn't answer in 15 s; Esi declines; Goran is too far, so...");
        clock.advance(Duration.ofSeconds(15));
        System.out.println("   expired offers: " + uber.expireOffers() + ", now offered to " + trip.offeredTo());
        uber.respond("d2", trip.id(), false);
        System.out.println("   after Esi declines: " + trip);

        step("Ana tries again once Dmitri is back on his phone");
        q = uber.quote("ana", home, office, VehicleType.ECONOMY);
        trip = uber.request("ana", q.id());
        uber.respond("d1", trip.id(), true);
        System.out.println("   " + trip + "  " + trip.history().get(trip.history().size() - 1));
        attempt(() -> uber.request("ana", uber.quote("ana", home, office, VehicleType.ECONOMY).id()));

        step("Ride");
        clock.advance(Duration.ofMinutes(3));
        uber.arrived("d1", trip.id());
        uber.start("d1", trip.id());
        clock.advance(Duration.ofMinutes(21));
        Trip done = uber.complete("d1", trip.id(), 10.4);
        System.out.println("   " + done + " (quoted " + money(q.estimateCents()) + ")");
        uber.rateDriver("ana", trip.id(), 5);
        uber.rateRider("d1", trip.id(), 4);
        attempt(() -> {
            uber.rateDriver("ana", done.id(), 1);
            return "ok";
        });

        step("Rush hour: many requests near the office, few drivers -> surge");
        for (int i = 0; i < 6; i++) {
            uber.addRider("r" + i, "Rider" + i);
            uber.quote("r" + i, office, home, VehicleType.ECONOMY);
        }
        FareQuote surge = uber.quote("ben", office, home, VehicleType.ECONOMY);
        System.out.println("   Ben's quote: " + surge);
        Trip benTrip = uber.request("ben", surge.id());
        uber.respond(benTrip.offeredTo().id(), benTrip.id(), true);
        clock.advance(Duration.ofMinutes(3));
        System.out.println("   Ben cancels 3 minutes after assignment: " + uber.cancelByRider("ben", benTrip.id()));

        step("A driver drops an accepted trip; the rider is re-matched");
        FareQuote q3 = uber.quote("ana", office, home, VehicleType.ECONOMY);
        Trip t3 = uber.request("ana", q3.id());
        String first = t3.offeredTo().id();
        uber.respond(first, t3.id(), true);
        uber.cancelByDriver(first, t3.id());
        System.out.println("   " + first + " cancelled -> " + t3 + ", now offered to " + t3.offeredTo());
        System.out.println("\n   payments net: " + money(payments.netCollected()) + "; Dmitri rated "
                + uber.driver("d1").rating().average());
    }

    private static String money(long c) {
        return String.format("$%d.%02d", c / 100, c % 100);
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (RideException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
