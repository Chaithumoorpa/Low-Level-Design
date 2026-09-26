package com.lld.booking.ride;

import com.lld.booking.ride.geo.GridIndex;
import com.lld.booking.ride.model.Driver;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RideServiceTest {

    private static final Location HOME = new Location(0, 0);
    private static final Location OFFICE = new Location(6, 8);                 // 10 km away

    private ManualClock clock;
    private FakePayments payments;
    private RideService uber;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2027-11-05T18:00:00Z"));
        payments = new FakePayments();
        uber = new RideService(new FareCalculator(), SurgePolicy.none(), payments, clock);
        uber.addRider("ana", "Ana");
        uber.addRider("ben", "Ben");
    }

    private Driver online(String id, VehicleType type, double x, double y) {
        uber.addDriver(id, id, type, new Location(x, y));
        uber.goOnline(id, new Location(x, y));
        return uber.driver(id);
    }

    private Trip request(String rider, Location from, Location to) {
        return uber.request(rider, uber.quote(rider, from, to, VehicleType.ECONOMY).id());
    }

    // ------------------------------------------------------------------ geo index

    @Nested
    class Geo {

        @Test
        void gridSearchMatchesBruteForce() {
            Random r = new Random(4);
            GridIndex grid = new GridIndex(0.7);
            Map<String, Location> all = new HashMap<>();
            for (int i = 0; i < 400; i++) {
                Location l = new Location(r.nextDouble() * 20 - 10, r.nextDouble() * 20 - 10);
                grid.put("d" + i, l);
                all.put("d" + i, l);
            }
            for (int i = 0; i < 100; i += 3) {                                  // moves and removals
                Location l = new Location(r.nextDouble() * 20 - 10, r.nextDouble() * 20 - 10);
                grid.put("d" + i, l);
                all.put("d" + i, l);
            }
            for (int i = 1; i < 60; i += 5) {
                grid.remove("d" + i);
                all.remove("d" + i);
            }
            for (int q = 0; q < 50; q++) {
                Location c = new Location(r.nextDouble() * 20 - 10, r.nextDouble() * 20 - 10);
                double radius = r.nextDouble() * 4;
                List<String> expected = all.entrySet().stream()
                        .filter(e -> e.getValue().distanceTo(c) <= radius)
                        .sorted(Comparator.comparingDouble((Map.Entry<String, Location> e) -> e.getValue().distanceTo(c))
                                .thenComparing(Map.Entry::getKey))
                        .map(Map.Entry::getKey).limit(7).toList();
                assertEquals(expected, grid.nearby(c, radius, id -> true, 7));
            }
            assertEquals(all.size(), grid.size());
        }
    }

    // ------------------------------------------------------------------ pricing

    @Nested
    class Pricing {

        @ParameterizedTest(name = "{0} {1} km {2} min surge {3} -> {4}c")
        @CsvSource({"ECONOMY,10,20,10000,1950", "ECONOMY,1,2,10000,600", "PREMIUM,10,20,10000,3300",
                "ECONOMY,10,20,15000,2925", "XL,0.5,1,25000,1313", "XL,0.5,1,10000,1000"})
        void fares(VehicleType type, double km, long min, int surge, long cents) {
            assertEquals(cents, new FareCalculator().fare(type, km, min, surge));
        }

        @ParameterizedTest(name = "demand {0} supply {1} -> {2}")
        @CsvSource({"0,0,10000", "3,3,10000", "4,2,15000", "3,2,13000", "5,0,25000", "10,1,25000", "5,4,11000"})
        void surge(int demand, int supply, int bps) {
            assertEquals(bps, SurgePolicy.standard().surgeBps(demand, supply), "x1.25 rounds to x1.3 (0.1 steps)");
        }

        @Test
        void surgeIsLockedByTheQuote() {
            RideService surging = new RideService(new FareCalculator(), SurgePolicy.standard(), payments, clock);
            surging.addRider("ana", "Ana");
            surging.addDriver("d", "d", VehicleType.ECONOMY, HOME);
            surging.goOnline("d", HOME);
            FareQuote calm = surging.quote("ana", HOME, OFFICE, VehicleType.ECONOMY);
            assertEquals(10_000, calm.surgeBps());
            for (int i = 0; i < 4; i++) {
                surging.addRider("x" + i, "x");
                surging.quote("x" + i, HOME, OFFICE, VehicleType.ECONOMY);
            }
            FareQuote busy = surging.quote("ana", HOME, OFFICE, VehicleType.ECONOMY);
            assertEquals(25_000, busy.surgeBps(), "6 requests for 1 driver");
            Trip t = surging.request("ana", calm.id());
            surging.respond("d", t.id(), true);
            surging.arrived("d", t.id());
            surging.start("d", t.id());
            clock.advance(Duration.ofMinutes(20));
            assertEquals(1950, surging.complete("d", t.id(), 10).fareCents(), "calm quote's surge applies");
            clock.advance(Duration.ofMinutes(5));
            assertEquals(10_000, surging.quote("ana", HOME, OFFICE, VehicleType.ECONOMY).surgeBps(), "demand window passed");
        }
    }

    // ------------------------------------------------------------------ matching

    @Nested
    class Matching {

        @Test
        void nearestIdleDriverOfTheRightProduct() {
            online("premium", VehicleType.PREMIUM, 0.1, 0);
            online("far", VehicleType.ECONOMY, 3, 0);
            online("near", VehicleType.ECONOMY, 1, 0);
            online("outside", VehicleType.ECONOMY, 6, 0);
            Trip t = request("ana", HOME, OFFICE);
            assertEquals("near", t.offeredTo().id());
            assertEquals(Driver.Status.OFFERED, uber.driver("near").status());
        }

        @Test
        void declinesAndTimeoutsMoveDownTheListThenNoDrivers() {
            online("a", VehicleType.ECONOMY, 1, 0);
            online("b", VehicleType.ECONOMY, 2, 0);
            online("c", VehicleType.ECONOMY, 7, 0);                      // outside 5 km
            Trip t = request("ana", HOME, OFFICE);
            uber.respond("a", t.id(), false);
            assertEquals("b", t.offeredTo().id());
            assertEquals(Driver.Status.AVAILABLE, uber.driver("a").status());
            clock.advance(Duration.ofSeconds(14));
            assertEquals(0, uber.expireOffers());
            clock.advance(Duration.ofSeconds(1));
            assertEquals(1, uber.expireOffers());
            assertEquals(Trip.Status.NO_DRIVERS, t.status());
            assertTrue(uber.activeTrip("ana").isEmpty(), "Ana can request again");
        }

        @Test
        void lateAcceptIsRejected() {
            online("a", VehicleType.ECONOMY, 1, 0);
            online("b", VehicleType.ECONOMY, 2, 0);
            Trip t = request("ana", HOME, OFFICE);
            clock.advance(Duration.ofSeconds(15));
            assertThrows(RideException.class, () -> uber.respond("a", t.id(), true));
            assertEquals("b", t.offeredTo().id());
            assertThrows(RideException.class, () -> uber.respond("a", t.id(), true), "no longer offered to a");
        }

        @Test
        void acceptedDriverLeavesTheSearchIndex() {
            online("a", VehicleType.ECONOMY, 1, 0);
            Trip t = request("ana", HOME, OFFICE);
            uber.respond("a", t.id(), true);
            assertEquals(Trip.Status.DRIVER_ASSIGNED, t.status());
            assertEquals(List.of(), uber.idleDriversNear(HOME, 10));
            Trip other = request("ben", HOME, OFFICE);
            assertEquals(Trip.Status.NO_DRIVERS, other.status());
        }

        @Test
        void requestRules() {
            online("a", VehicleType.ECONOMY, 1, 0);
            FareQuote q = uber.quote("ana", HOME, OFFICE, VehicleType.ECONOMY);
            assertThrows(RideException.class, () -> uber.request("ben", q.id()), "someone else's quote");
            clock.advance(Duration.ofMinutes(2));
            assertThrows(RideException.class, () -> uber.request("ana", q.id()), "expired");
            request("ana", HOME, OFFICE);
            assertThrows(RideException.class, () -> request("ana", HOME, OFFICE), "one active trip");
            assertThrows(RideException.class, () -> uber.goOffline("a"), "has an offer");
            assertThrows(RideException.class, () -> uber.goOnline("a", HOME));
        }
    }

    // ------------------------------------------------------------------ trip & cancellation

    @Nested
    class TripLifecycle {

        @Test
        void fullTripChargesRealDistanceAndTime() {
            Driver d = online("a", VehicleType.ECONOMY, 1, 0);
            Trip t = request("ana", HOME, OFFICE);
            uber.respond("a", t.id(), true);
            assertThrows(RideException.class, () -> uber.start("a", t.id()), "arrive first");
            uber.arrived("a", t.id());
            uber.start("a", t.id());
            clock.advance(Duration.ofMinutes(24).plusSeconds(10));
            assertEquals(250 + 120 * 12 + 25 * 25, uber.complete("a", t.id(), 12).fareCents(), "25 started minutes");
            assertEquals(t.fareCents(), payments.netCollected());
            assertEquals(OFFICE, d.location());
            assertEquals(List.of("a"), uber.idleDriversNear(OFFICE, 1), "back in the index at the drop-off");
            assertThrows(RideException.class, () -> uber.complete("a", t.id(), 12));
        }

        @Test
        void riderCancellationFeeAfterTwoMinutes() {
            online("a", VehicleType.ECONOMY, 1, 0);
            Trip free = request("ana", HOME, OFFICE);
            uber.cancelByRider("ana", free.id());
            assertEquals(Driver.Status.AVAILABLE, uber.driver("a").status(), "offer withdrawn");
            Trip t = request("ana", HOME, OFFICE);
            uber.respond("a", t.id(), true);
            clock.advance(Duration.ofMinutes(2));
            uber.cancelByRider("ana", t.id());
            assertEquals(0, t.cancellationFeeCents(), "exactly 2 minutes is still free");
            Trip late = request("ana", HOME, OFFICE);
            uber.respond("a", late.id(), true);
            clock.advance(Duration.ofMinutes(2).plusSeconds(1));
            uber.cancelByRider("ana", late.id());
            assertEquals(RideService.CANCEL_FEE, late.cancellationFeeCents());
            assertEquals(RideService.CANCEL_FEE, payments.netCollected());
            Trip started = request("ana", HOME, OFFICE);
            uber.respond("a", started.id(), true);
            uber.arrived("a", started.id());
            uber.start("a", started.id());
            assertThrows(RideException.class, () -> uber.cancelByRider("ana", started.id()));
            assertThrows(RideException.class, () -> uber.cancelByRider("ben", started.id()));
        }

        @Test
        void driverCancellationRematchesWithoutThem() {
            online("a", VehicleType.ECONOMY, 1, 0);
            online("b", VehicleType.ECONOMY, 2, 0);
            Trip t = request("ana", HOME, OFFICE);
            uber.respond("a", t.id(), true);
            uber.cancelByDriver("a", t.id());
            assertEquals(Trip.Status.MATCHING, t.status());
            assertNull(t.driver());
            assertEquals("b", t.offeredTo().id(), "a is nearest but already passed on this trip");
        }

        @Test
        void ratingsOnceEachAfterCompletion() {
            Driver d = online("a", VehicleType.ECONOMY, 1, 0);
            Trip t = request("ana", HOME, OFFICE);
            uber.respond("a", t.id(), true);
            assertThrows(RideException.class, () -> uber.rateDriver("ana", t.id(), 5));
            uber.arrived("a", t.id());
            uber.start("a", t.id());
            uber.complete("a", t.id(), 10);
            uber.rateDriver("ana", t.id(), 3);
            uber.rateRider("a", t.id(), 5);
            assertThrows(RideException.class, () -> uber.rateDriver("ana", t.id(), 5));
            assertThrows(RideException.class, () -> uber.rateRider("a", t.id(), 1));
            assertEquals(3.0, d.rating().average());
            assertThrows(RideException.class, () -> uber.rateDriver("ben", t.id(), 5));
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentRequestsNeverShareADriver() throws Exception {
        for (int i = 0; i < 5; i++) {
            online("d" + i, VehicleType.ECONOMY, i * 0.2, 0);
        }
        List<String> riders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            uber.addRider("r" + i, "r" + i);
            riders.add("r" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Trip>> futures = new ArrayList<>();
        for (String r : riders) {
            futures.add(pool.submit(() -> {
                start.await();
                return request(r, HOME, OFFICE);
            }));
        }
        start.countDown();
        Set<String> offered = new HashSet<>();
        int matching = 0;
        for (Future<Trip> f : futures) {
            Trip t = f.get(10, TimeUnit.SECONDS);
            if (t.status() == Trip.Status.MATCHING) {
                assertTrue(offered.add(t.offeredTo().id()), "driver offered twice");
                matching++;
            } else {
                assertEquals(Trip.Status.NO_DRIVERS, t.status());
            }
        }
        pool.shutdown();
        assertEquals(5, matching);
    }
}
