package com.lld.management.parkinglot;

import com.lld.management.parkinglot.allocation.BestFitStrategy;
import com.lld.management.parkinglot.allocation.NearestSpotStrategy;
import com.lld.management.parkinglot.allocation.SpotAllocationStrategy;
import com.lld.management.parkinglot.lot.DisplayBoard;
import com.lld.management.parkinglot.lot.ManualClock;
import com.lld.management.parkinglot.lot.ParkingEventListener;
import com.lld.management.parkinglot.lot.ParkingFloor;
import com.lld.management.parkinglot.lot.ParkingLot;
import com.lld.management.parkinglot.model.ParkingException;
import com.lld.management.parkinglot.model.Receipt;
import com.lld.management.parkinglot.model.SpotSize;
import com.lld.management.parkinglot.model.Ticket;
import com.lld.management.parkinglot.model.Vehicle;
import com.lld.management.parkinglot.model.VehicleType;
import com.lld.management.parkinglot.payment.CardPayment;
import com.lld.management.parkinglot.payment.CashPayment;
import com.lld.management.parkinglot.payment.PaymentMethod;
import com.lld.management.parkinglot.pricing.HourlyPricing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkingLotTest {

    private static final PaymentMethod APPROVE = new CardPayment("ok", (c, a) -> true);
    private static final PaymentMethod DECLINE = new CardPayment("bad", (c, a) -> false);

    private ManualClock clock;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-05-04T08:00:00Z"));
    }

    /** Spots are numbered in the order of the layout: lower number = nearer the ramp. */
    private static ParkingFloor floor(int level, Object... sizeAndCount) {
        Map<SpotSize, Integer> layout = new LinkedHashMap<>();
        for (int i = 0; i < sizeAndCount.length; i += 2) {
            layout.put((SpotSize) sizeAndCount[i], (Integer) sizeAndCount[i + 1]);
        }
        return new ParkingFloor(level, layout);
    }

    private ParkingLot lot(SpotAllocationStrategy strategy, ParkingFloor... floors) {
        return new ParkingLot("Test", List.of(floors), strategy, HourlyPricing.standard(), clock);
    }

    private ParkingLot standardLot() {
        return lot(new BestFitStrategy(),
                floor(0, SpotSize.MOTORCYCLE, 1, SpotSize.COMPACT, 2, SpotSize.EV, 1, SpotSize.LARGE, 1),
                floor(1, SpotSize.MOTORCYCLE, 1, SpotSize.COMPACT, 2, SpotSize.EV, 1, SpotSize.LARGE, 1));
    }

    // ------------------------------------------------------------------ allocation

    @Nested
    class Allocation {

        @Test
        void bestFitKeepsLargeSpotsForLargeVehicles() {
            ParkingLot lot = lot(new BestFitStrategy(), floor(0, SpotSize.LARGE, 1, SpotSize.COMPACT, 1));
            Ticket car = lot.enter(Vehicle.of("CAR1", VehicleType.CAR), "G");
            assertEquals(SpotSize.COMPACT, car.spot().size());
            Ticket truck = lot.enter(Vehicle.of("TRK1", VehicleType.TRUCK), "G");
            assertEquals(SpotSize.LARGE, truck.spot().size());
        }

        @Test
        void nearestTakesTheClosestSpotEvenIfBigger() {
            ParkingLot lot = lot(new NearestSpotStrategy(), floor(0, SpotSize.LARGE, 1, SpotSize.COMPACT, 1));
            Ticket car = lot.enter(Vehicle.of("CAR1", VehicleType.CAR), "G");
            assertEquals("F0-L01", car.spot().id());
            assertThrows(ParkingException.class, () -> lot.enter(Vehicle.of("TRK1", VehicleType.TRUCK), "G"),
                    "the price of 'nearest': the only large spot is gone");
        }

        @Test
        void bestFitPrefersSmallerSizeOnUpperFloorOverBiggerSizeBelow() {
            ParkingLot lot = lot(new BestFitStrategy(), floor(0, SpotSize.LARGE, 1), floor(1, SpotSize.COMPACT, 1));
            assertEquals("F1-C01", lot.enter(Vehicle.of("CAR1", VehicleType.CAR), "G").spot().id());
        }

        @Test
        void evPrefersChargingSpotThenFallsBackToCompact() {
            ParkingLot lot = lot(new BestFitStrategy(), floor(0, SpotSize.COMPACT, 1, SpotSize.EV, 1));
            assertEquals(SpotSize.EV, lot.enter(Vehicle.of("EV1", VehicleType.ELECTRIC_CAR), "G").spot().size());
            assertEquals(SpotSize.COMPACT, lot.enter(Vehicle.of("EV2", VehicleType.ELECTRIC_CAR), "G").spot().size());
        }

        @Test
        void ordinaryCarNeverTakesChargingSpot() {
            ParkingLot lot = lot(new BestFitStrategy(), floor(0, SpotSize.EV, 3));
            assertThrows(ParkingException.class, () -> lot.enter(Vehicle.of("CAR1", VehicleType.CAR), "G"));
            assertFalse(lot.hasSpaceFor(VehicleType.CAR));
            assertTrue(lot.hasSpaceFor(VehicleType.ELECTRIC_CAR));
        }

        @Test
        void motorcycleUsesMotorcycleSpotFirst() {
            ParkingLot lot = standardLot();
            assertEquals(SpotSize.MOTORCYCLE, lot.enter(Vehicle.of("M1", VehicleType.MOTORCYCLE), "G").spot().size());
            assertEquals("F1-M01", lot.enter(Vehicle.of("M2", VehicleType.MOTORCYCLE), "G").spot().id());
            assertEquals(SpotSize.COMPACT, lot.enter(Vehicle.of("M3", VehicleType.MOTORCYCLE), "G").spot().size());
        }
    }

    // ------------------------------------------------------------------ entry & exit rules

    @Nested
    class EntryAndExit {

        @Test
        void fullLotRefusesAndNotifies() {
            ParkingLot lot = lot(new BestFitStrategy(), floor(0, SpotSize.LARGE, 1));
            List<VehicleType> turnedAway = new ArrayList<>();
            lot.addListener(new ParkingEventListener() {
                @Override
                public void onFull(VehicleType type) {
                    turnedAway.add(type);
                }
            });
            lot.enter(Vehicle.of("V1", VehicleType.VAN), "G");
            ParkingException e = assertThrows(ParkingException.class,
                    () -> lot.enter(Vehicle.of("V2", VehicleType.VAN), "G"));
            assertTrue(e.getMessage().contains("FULL"));
            assertEquals(List.of(VehicleType.VAN), turnedAway);
            assertEquals(1, lot.parkedCount());
        }

        @Test
        void samePlateCannotBeInsideTwiceEvenWrittenDifferently() {
            ParkingLot lot = standardLot();
            lot.enter(Vehicle.of("KA-01 ab 1234", VehicleType.CAR), "G");
            assertThrows(ParkingException.class, () -> lot.enter(Vehicle.of("KA01AB1234", VehicleType.CAR), "G"));
            assertEquals(1, lot.parkedCount());
        }

        @Test
        void exitFreesTheSpotForTheNextVehicle() {
            ParkingLot lot = lot(new BestFitStrategy(), floor(0, SpotSize.COMPACT, 1));
            Ticket t = lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
            clock.advance(Duration.ofHours(1));
            lot.exit(t.id(), APPROVE);
            assertEquals(t.spot().id(), lot.enter(Vehicle.of("B", VehicleType.CAR), "G").spot().id());
        }

        @Test
        void sameVehicleCanComeBackAfterLeaving() {
            ParkingLot lot = standardLot();
            Ticket t = lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
            lot.exit(t.id(), APPROVE);
            Ticket again = lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
            assertNotEquals(t.id(), again.id());
        }

        @Test
        void ticketCannotBeUsedTwice() {
            ParkingLot lot = standardLot();
            Ticket t = lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
            clock.advance(Duration.ofHours(1));
            lot.exit(t.id(), APPROVE);
            assertThrows(ParkingException.class, () -> lot.exit(t.id(), APPROVE));
            assertThrows(ParkingException.class, () -> lot.exit("T999", APPROVE));
            assertEquals(1, lot.receipts().size());
        }

        @Test
        void declinedPaymentKeepsVehicleParkedAndTicketValid() {
            ParkingLot lot = standardLot();
            Ticket t = lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
            clock.advance(Duration.ofHours(2));
            assertThrows(ParkingException.class, () -> lot.exit(t.id(), DECLINE));
            assertThrows(ParkingException.class, () -> lot.exit(t.id(), new CashPayment(100)), "not enough cash");
            assertEquals(1, lot.parkedCount());
            assertEquals(0, lot.revenueCents());

            Receipt r = lot.exit(t.id(), new CashPayment(1000));
            assertEquals(400, r.feeCents());
            assertEquals("CASH, change 600c", r.paymentReference());
            assertEquals(0, lot.parkedCount());
            assertEquals(400, lot.revenueCents());
        }

        @Test
        void lostTicketFoundByPlateAndChargedAtLeastTheDailyMaximum() {
            ParkingLot lot = standardLot();
            Ticket t = lot.enter(Vehicle.of("KA 01", VehicleType.CAR), "G");
            clock.advance(Duration.ofMinutes(30));
            Receipt r = lot.exitWithLostTicket("ka-01", APPROVE);
            assertTrue(r.lostTicket());
            assertEquals(2000, r.feeCents());
            assertThrows(ParkingException.class, () -> lot.exit(t.id(), APPROVE), "the lost ticket is void now");
            assertThrows(ParkingException.class, () -> lot.exitWithLostTicket("KA01", APPROVE));
        }

        @Test
        void lostTicketAfterMultiDayStayChargesTheRealFee() {
            ParkingLot lot = standardLot();
            lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
            clock.advance(Duration.ofDays(3));
            assertEquals(3 * 2000, lot.exitWithLostTicket("A", APPROVE).feeCents());
        }

        @Test
        void outOfServiceSpotIsSkippedAndCannotCloseAnOccupiedSpot() {
            ParkingLot lot = lot(new BestFitStrategy(), floor(0, SpotSize.COMPACT, 2));
            lot.setOutOfService("F0-C01", true);
            assertEquals("F0-C02", lot.enter(Vehicle.of("A", VehicleType.CAR), "G").spot().id());
            assertThrows(ParkingException.class, () -> lot.enter(Vehicle.of("B", VehicleType.CAR), "G"));
            assertThrows(IllegalStateException.class, () -> lot.setOutOfService("F0-C02", true));

            lot.setOutOfService("F0-C01", false);
            assertEquals("F0-C01", lot.enter(Vehicle.of("B", VehicleType.CAR), "G").spot().id());
            assertThrows(ParkingException.class, () -> lot.setOutOfService("F9-C01", true));
        }
    }

    // ------------------------------------------------------------------ pricing

    @Nested
    class Pricing {

        private final HourlyPricing pricing = HourlyPricing.standard();

        @ParameterizedTest(name = "{0} for {1} min -> {2}c")
        @CsvSource({
                "CAR,           0,     0",
                "CAR,          15,     0",     // grace period is inclusive
                "CAR,          16,   200",     // past grace: the first hour is charged
                "CAR,          60,   200",
                "CAR,          61,   400",     // every started hour counts
                "CAR,         150,   600",
                "MOTORCYCLE,  150,   300",
                "TRUCK,       150,  1200",
                "CAR,         600,  2000",     // 10 h reaches the daily cap
                "CAR,        1439,  2000",     // capped all day
                "CAR,        1440,  2000",     // exactly one day
                "CAR,        1500,  2200",     // 1 day + 1 started hour
                "TRUCK,      2880,  8000",     // 2 full days
        })
        void hourlyWithGraceAndDailyCap(VehicleType type, long minutes, long expected) {
            assertEquals(expected, pricing.fee(type, Duration.ofMinutes(minutes)));
        }

        @Test
        void oneSecondIntoANewHourIsANewHour() {
            assertEquals(400, pricing.fee(VehicleType.CAR, Duration.ofHours(1).plusSeconds(1)));
            assertEquals(400, pricing.fee(VehicleType.CAR, Duration.ofHours(1).plusNanos(1)));
        }

        @Test
        void negativeStayIsRejectedAndMissingRatesAreCaught() {
            assertThrows(IllegalArgumentException.class, () -> pricing.fee(VehicleType.CAR, Duration.ofMinutes(-1)));
            assertThrows(IllegalArgumentException.class,
                    () -> new HourlyPricing(Map.of(VehicleType.CAR, 1L), Map.of(VehicleType.CAR, 1L), Duration.ZERO));
        }

        @Test
        void gracePeriodExitNeedsNoPayment() {
            ParkingLot lot = standardLot();
            Ticket t = lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
            clock.advance(Duration.ofMinutes(10));
            Receipt r = lot.exit(t.id(), DECLINE);         // the card is never even charged
            assertEquals(0, r.feeCents());
        }
    }

    // ------------------------------------------------------------------ observers

    @Test
    void displayBoardTracksEveryChange() {
        ParkingLot lot = standardLot();
        DisplayBoard board = new DisplayBoard(lot);
        assertEquals(2, board.free(0, SpotSize.COMPACT));

        Ticket a = lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
        lot.enter(Vehicle.of("B", VehicleType.CAR), "G");
        assertEquals(0, board.free(0, SpotSize.COMPACT));
        assertTrue(board.render().contains("COMPACT=FULL"));

        lot.exit(a.id(), APPROVE);
        assertEquals(1, board.free(0, SpotSize.COMPACT));

        lot.setOutOfService("F1-L05", true);
        assertEquals(0, board.free(1, SpotSize.LARGE));
        assertEquals(lot.freeSpots().get(SpotSize.LARGE), board.free(0, SpotSize.LARGE) + board.free(1, SpotSize.LARGE));
    }

    @Test
    void listenersSeeEntriesAndExitsInOrder() {
        ParkingLot lot = standardLot();
        List<String> events = new ArrayList<>();
        lot.addListener(new ParkingEventListener() {
            @Override
            public void onEntry(Ticket t) {
                events.add("in " + t.vehicle().licensePlate());
            }

            @Override
            public void onExit(Receipt r) {
                events.add("out " + r.ticket().vehicle().licensePlate());
            }
        });
        Ticket a = lot.enter(Vehicle.of("A", VehicleType.CAR), "G");
        lot.enter(Vehicle.of("B", VehicleType.VAN), "G");
        lot.exit(a.id(), APPROVE);
        assertEquals(List.of("in A", "in B", "out A"), events);
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentGatesNeverShareASpot() throws Exception {
        int compactSpots = 50;
        ParkingLot lot = lot(new BestFitStrategy(), floor(0, SpotSize.COMPACT, 25), floor(1, SpotSize.COMPACT, 25));
        int drivers = 200;
        ExecutorService gates = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger refused = new AtomicInteger();
        List<Future<Ticket>> results = new ArrayList<>();
        for (int i = 0; i < drivers; i++) {
            String plate = "CAR" + i;
            results.add(gates.submit(() -> {
                start.await();
                try {
                    return lot.enter(Vehicle.of(plate, VehicleType.CAR), "G");
                } catch (ParkingException full) {
                    refused.incrementAndGet();
                    return null;
                }
            }));
        }
        start.countDown();
        Set<String> spots = new HashSet<>();
        List<Ticket> tickets = new ArrayList<>();
        for (Future<Ticket> f : results) {
            Ticket t = f.get(10, TimeUnit.SECONDS);
            if (t != null) {
                assertTrue(spots.add(t.spot().id()), "spot handed out twice: " + t.spot().id());
                tickets.add(t);
            }
        }
        assertEquals(compactSpots, tickets.size());
        assertEquals(drivers - compactSpots, refused.get());
        assertEquals(0, lot.freeSpots().get(SpotSize.COMPACT));

        // Everyone leaves at once, and each ticket is presented twice by racing exits.
        Collections.shuffle(tickets);
        AtomicInteger paid = new AtomicInteger();
        List<Future<?>> exits = new ArrayList<>();
        for (Ticket t : tickets) {
            for (int copy = 0; copy < 2; copy++) {
                exits.add(gates.submit(() -> {
                    try {
                        lot.exit(t.id(), APPROVE);
                        paid.incrementAndGet();
                    } catch (ParkingException alreadyUsed) {
                        // expected for the losing copy
                    }
                }));
            }
        }
        for (Future<?> f : exits) {
            f.get(10, TimeUnit.SECONDS);
        }
        gates.shutdown();
        assertEquals(compactSpots, paid.get());
        assertEquals(compactSpots, lot.freeSpots().get(SpotSize.COMPACT));
        assertEquals(0, lot.parkedCount());
    }
}
