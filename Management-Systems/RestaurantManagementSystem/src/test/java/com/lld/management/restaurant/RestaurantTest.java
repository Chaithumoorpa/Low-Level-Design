package com.lld.management.restaurant;

import com.lld.management.restaurant.billing.Bill;
import com.lld.management.restaurant.billing.BillCalculator;
import com.lld.management.restaurant.billing.BillLine;
import com.lld.management.restaurant.billing.HappyHourRule;
import com.lld.management.restaurant.billing.PricingRule;
import com.lld.management.restaurant.billing.ServiceChargeRule;
import com.lld.management.restaurant.billing.TaxRule;
import com.lld.management.restaurant.core.ManualClock;
import com.lld.management.restaurant.core.Restaurant;
import com.lld.management.restaurant.core.Restaurant.Line;
import com.lld.management.restaurant.core.RestaurantListener;
import com.lld.management.restaurant.model.Booking;
import com.lld.management.restaurant.model.MenuItem;
import com.lld.management.restaurant.model.MenuItem.Category;
import com.lld.management.restaurant.model.MenuItem.Station;
import com.lld.management.restaurant.model.OrderItem;
import com.lld.management.restaurant.model.RestaurantException;
import com.lld.management.restaurant.model.Tab;
import com.lld.management.restaurant.model.Table;
import com.lld.management.restaurant.seating.TableAssignmentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestaurantTest {

    private ManualClock clock;
    private Restaurant r;
    private LocalDateTime today;
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-10-09T17:30:00Z"));
        r = restaurant(TableAssignmentStrategy.smallestFit(), new BillCalculator(List.of(
                new HappyHourRule(Category.DRINK, LocalTime.of(17, 0), LocalTime.of(19, 0), 5000),
                new ServiceChargeRule(6, 1000),
                new TaxRule(800))));
        today = r.now().toLocalDate().atStartOfDay();
    }

    private Restaurant restaurant(TableAssignmentStrategy seating, BillCalculator billing) {
        Restaurant x = new Restaurant(seating, billing, Duration.ofHours(2), Duration.ofMinutes(15), clock);
        x.addListener(new RestaurantListener() {
            @Override
            public void onTicket(Station station, String tableId, List<OrderItem> items) {
                events.add(station + " " + tableId + " " + items.size());
            }

            @Override
            public void onItemReady(OrderItem item) {
                events.add("ready " + item.id());
            }
        });
        x.addMenuItem(new MenuItem("SOUP", "Soup", Category.STARTER, Station.COLD, 700));
        x.addMenuItem(new MenuItem("STEAK", "Steak", Category.MAIN, Station.GRILL, 2400));
        x.addMenuItem(new MenuItem("TART", "Tart", Category.DESSERT, Station.PASTRY, 800));
        x.addMenuItem(new MenuItem("WINE", "Wine", Category.DRINK, Station.BAR, 900));
        x.addTable(new Table("T1", 2));
        x.addTable(new Table("T2", 4));
        x.addTable(new Table("T3", 6));
        return x;
    }

    private void minutes(int n) {
        clock.advance(Duration.ofMinutes(n));
    }

    private void serveAll(String tableId) {
        for (OrderItem i : r.tab(tableId).items()) {
            if (i.status() == OrderItem.Status.PLACED) {
                r.startNext(i.item().station());
            }
        }
        for (OrderItem i : r.tab(tableId).items()) {
            if (i.status() == OrderItem.Status.PREPARING) {
                r.markReady(i.id());
            }
            if (i.status() == OrderItem.Status.READY) {
                r.markServed(i.id());
            }
        }
    }

    // ------------------------------------------------------------------ seating

    @Nested
    class Seating {

        @Test
        void smallestFittingTableForBookings() {
            assertEquals("T1", r.book("A", 2, today.withHour(19)).table().id());
            assertEquals("T2", r.book("B", 2, today.withHour(19)).table().id(), "T1 taken, next smallest");
            assertEquals("T3", r.book("C", 5, today.withHour(19)).table().id());
            assertThrows(RestaurantException.class, () -> r.book("D", 1, today.withHour(20)), "all overlap");
            assertEquals("T1", r.book("E", 2, today.withHour(21)).table().id(), "slot [21,23) is after [19,21)");
            assertThrows(RestaurantException.class, () -> r.book("F", 7, today.withHour(22)), "no table seats 7");
            assertThrows(RestaurantException.class, () -> r.book("G", 2, today.withHour(17)), "in the past");
        }

        @Test
        void firstAvailableStrategyWastesBigTables() {
            Restaurant simple = restaurant(TableAssignmentStrategy.firstAvailable(), new BillCalculator(List.of()));
            assertEquals("T1", simple.book("A", 2, today.withHour(19)).table().id());
            assertEquals("T2", simple.book("B", 1, today.withHour(19)).table().id());
            assertEquals("T3", simple.book("C", 2, today.withHour(19)).table().id());
            assertThrows(RestaurantException.class, () -> simple.book("D", 5, today.withHour(19)),
                    "a party of 5 is turned away that smallest-fit would have seated");
        }

        @Test
        void walkInsAvoidTablesBookedSoon() {
            r.book("A", 2, today.withHour(18));                                  // T1 from 18:00
            r.book("B", 6, today.withHour(19).withMinute(29));                   // T3 from 19:29
            Tab walkIn = r.seatWalkIn(2);
            assertEquals("T2", walkIn.table().id(), "T1 is booked within the next 2 hours");
            assertThrows(RestaurantException.class, () -> r.seatWalkIn(5), "T3 is free now but booked in 1h59");
            minutes(1);                                                          // 17:31: a party of 2 arriving
            assertThrows(RestaurantException.class, () -> r.seatWalkIn(1), "T1 booked, T2 taken, T3 booked");
        }

        @Test
        void checkInRulesAndNoShows() {
            Booking early = r.book("A", 2, today.withHour(18));
            Booking late = r.book("B", 4, today.withHour(18));
            minutes(30);
            assertEquals(Table.Status.OCCUPIED, r.checkIn(early.id()).table().status());
            assertThrows(RestaurantException.class, () -> r.checkIn(early.id()), "already seated");
            minutes(16);                                                         // 18:16
            assertThrows(RestaurantException.class, () -> r.checkIn(late.id()));
            assertEquals(List.of(late), r.markNoShows());
            assertEquals(Booking.Status.NO_SHOW, late.status());
            assertEquals(List.of(), r.markNoShows());
        }

        @Test
        void cancelledBookingFreesTheSlot() {
            Booking b = r.book("A", 5, today.withHour(19));
            assertThrows(RestaurantException.class, () -> r.book("B", 5, today.withHour(19)));
            r.cancelBooking(b.id());
            assertEquals("T3", r.book("B", 5, today.withHour(19)).table().id());
            assertThrows(RestaurantException.class, () -> r.cancelBooking(b.id()));
        }

        @Test
        void bookedTableStillOccupiedMeansWait() {
            Tab walkIn = r.seatWalkIn(6);                                        // T3 now, nobody booked it
            Booking b = r.book("A", 6, today.withHour(19).withMinute(30));       // after the walk-in slot
            minutes(120);                                                        // 19:30, walk-in lingers
            assertThrows(RestaurantException.class, () -> r.checkIn(b.id()));
            r.order("T3", List.of(Line.of("SOUP", 1)));
            serveAll("T3");
            r.pay("T3", r.bill("T3").totalCents());
            assertThrows(RestaurantException.class, () -> r.checkIn(b.id()), "still being cleaned");
            r.markClean("T3");
            assertEquals("T3", r.checkIn(b.id()).table().id());
            assertTrue(!walkIn.isOpen());
        }
    }

    // ------------------------------------------------------------------ orders & kitchen

    @Nested
    class Kitchen {

        @Test
        void orderSplitsIntoOneTicketPerStation() {
            r.seatWalkIn(4);
            r.order("T2", List.of(Line.of("STEAK", 2), Line.of("WINE", 2), Line.of("SOUP", 1), Line.of("STEAK", 1)));
            assertEquals(List.of("GRILL T2 2", "COLD T2 1", "BAR T2 1"), events);
            assertEquals(2, r.queue(Station.GRILL).size());
            assertEquals(List.of(), r.queue(Station.PASTRY));
        }

        @Test
        void stationsAreFirstInFirstOut() {
            r.seatWalkIn(2);
            r.seatWalkIn(4);
            OrderItem first = r.order("T1", List.of(Line.of("STEAK", 1))).get(0);
            OrderItem second = r.order("T2", List.of(Line.of("STEAK", 1))).get(0);
            assertEquals(Optional.of(first), r.startNext(Station.GRILL));
            assertEquals(Optional.of(second), r.startNext(Station.GRILL));
            assertEquals(Optional.empty(), r.startNext(Station.GRILL));
        }

        @Test
        void soldOutRejectsTheWholeOrder() {
            r.seatWalkIn(2);
            r.setSoldOut("TART", true);
            assertThrows(RestaurantException.class, () -> r.order("T1", List.of(Line.of("SOUP", 1), Line.of("TART", 1))));
            assertEquals(List.of(), r.tab("T1").items());
            r.setSoldOut("TART", false);
            assertEquals(1, r.order("T1", List.of(Line.of("TART", 1))).size());
        }

        @Test
        void itemLifecycleIsEnforced() {
            r.seatWalkIn(2);
            OrderItem steak = r.order("T1", List.of(Line.of("STEAK", 1))).get(0);
            assertThrows(RestaurantException.class, () -> r.markReady(steak.id()), "not started");
            r.startNext(Station.GRILL);
            assertThrows(RestaurantException.class, () -> r.markServed(steak.id()), "not ready");
            r.markReady(steak.id());
            r.markServed(steak.id());
            assertEquals(OrderItem.Status.SERVED, steak.status());
            assertTrue(events.contains("ready " + steak.id()));
        }

        @Test
        void cancelBeforeCookingVoidAfter() {
            r.seatWalkIn(2);
            List<OrderItem> items = r.order("T1", List.of(Line.of("STEAK", 1), Line.of("SOUP", 1)));
            r.cancelItem(items.get(1).id());
            assertEquals(List.of(), r.queue(Station.COLD), "cancelled item leaves the station queue");
            r.startNext(Station.GRILL);
            assertThrows(RestaurantException.class, () -> r.cancelItem(items.get(0).id()));
            assertThrows(RestaurantException.class, () -> r.voidItem(items.get(0).id(), " "));
            r.voidItem(items.get(0).id(), "cold");
            assertThrows(RestaurantException.class, () -> r.voidItem(items.get(0).id(), "again"));
            assertThrows(RestaurantException.class, () -> r.voidItem(items.get(1).id(), "cancelled already"));
        }

        @Test
        void orderingNeedsASeatedParty() {
            assertThrows(RestaurantException.class, () -> r.order("T1", List.of(Line.of("SOUP", 1))));
            r.seatWalkIn(2);
            assertThrows(RestaurantException.class, () -> r.order("T1", List.of(Line.of("PIZZA", 1))));
            assertThrows(RestaurantException.class, () -> r.order("T1", List.of()));
        }
    }

    // ------------------------------------------------------------------ billing

    @Nested
    class Billing {

        @Test
        void rulesRunInOrderOnTheRunningTotal() {
            r.seatWalkIn(6);                                                     // T3, 17:30: happy hour
            r.order("T3", List.of(Line.of("STEAK", 4), Line.of("WINE", 6)));
            minutes(100);                                                        // 19:10, happy hour over
            r.order("T3", List.of(Line.of("WINE", 2)));
            Bill bill = r.bill("T3");
            assertEquals(9600 + 5400 + 1800, bill.itemsSubtotalCents());
            assertEquals(List.of(
                    new BillLine("Happy hour 50% off drink", -2700),
                    new BillLine("Service 10.0% (party of 6)", 1410),
                    new BillLine("Tax 8.0%", 1241)), bill.adjustments());
            assertEquals(16800 - 2700 + 1410 + 1241, bill.totalCents());
        }

        @Test
        void cancelledItemsVanishAndVoidedOnesShowAtZero() {
            r.seatWalkIn(2);
            List<OrderItem> items = r.order("T1", List.of(Line.of("STEAK", 1), Line.of("SOUP", 1), Line.of("TART", 1)));
            r.cancelItem(items.get(1).id());
            r.voidItem(items.get(2).id(), "birthday");
            Bill bill = r.bill("T1");
            assertEquals(List.of(new BillLine("1x Steak", 2400), new BillLine("1x Tart (comp: birthday)", 0)), bill.itemLines());
            assertEquals(2400 + 192, bill.totalCents(), "tax only");
        }

        @ParameterizedTest(name = "{0}c at {1} bps -> {2}c")
        @CsvSource({"1000,800,80", "1250,800,100", "1249,800,100", "1231,800,98", "15500,1000,1550", "5,5000,3", "0,800,0"})
        void percentagesRoundHalfUp(long amount, int bps, long expected) {
            assertEquals(expected, PricingRule.percentOf(amount, bps));
        }

        @Test
        void evenSplitAddsUpExactly() {
            assertEquals(List.of(3334L, 3333L, 3333L), BillCalculator.splitEvenly(10000, 3));
            assertEquals(List.of(2L, 2L, 1L, 1L), BillCalculator.splitEvenly(6, 4));
            for (int ways = 1; ways <= 9; ways++) {
                assertEquals(18414, BillCalculator.splitEvenly(18414, ways).stream().mapToLong(Long::longValue).sum());
            }
            assertThrows(IllegalArgumentException.class, () -> BillCalculator.splitEvenly(100, 0));
        }

        @Test
        void splitPaymentsCloseTheTabAndCleaningFreesTheTable() {
            r.seatWalkIn(4);
            r.order("T2", List.of(Line.of("STEAK", 2)));
            serveAll("T2");
            long total = r.bill("T2").totalCents();                             // 4800 + 384 tax
            assertEquals(5184, total);
            assertEquals(5184 - 2000, r.pay("T2", 2000));
            assertEquals(Table.Status.OCCUPIED, r.table("T2").status());
            assertEquals(0, r.pay("T2", 5000));                                  // overpay = tip
            assertEquals(Table.Status.CLEANING, r.table("T2").status());
            assertThrows(RestaurantException.class, () -> r.bill("T2"), "tab is closed");
            assertThrows(RestaurantException.class, () -> r.pay("T2", 1));
            r.markClean("T2");
            assertEquals(Table.Status.FREE, r.table("T2").status());
            assertThrows(RestaurantException.class, () -> r.markClean("T2"));
        }

        @Test
        void cantSettleWhileFoodIsStillComing() {
            r.seatWalkIn(2);
            r.order("T1", List.of(Line.of("STEAK", 1)));
            long total = r.bill("T1").totalCents();
            assertEquals(total - 1000, r.pay("T1", 1000), "a partial payment is fine");
            RestaurantException e = assertThrows(RestaurantException.class, () -> r.pay("T1", total));
            assertTrue(e.getMessage().contains("still on their way"));
            serveAll("T1");
            assertEquals(0, r.pay("T1", total - 1000));
            assertThrows(RestaurantException.class, () -> r.pay("T1", 0));
        }

        @Test
        void completedBookingNoLongerBlocksItsSlot() {
            Booking b = r.book("A", 2, today.withHour(18));
            minutes(30);
            r.checkIn(b.id());
            r.order("T1", List.of(Line.of("SOUP", 1)));
            serveAll("T1");
            r.pay("T1", r.bill("T1").totalCents());
            r.markClean("T1");
            assertEquals(Booking.Status.COMPLETED, b.status());
            assertEquals("T1", r.seatWalkIn(2).table().id(), "the rest of the 18:00-20:00 slot is free again");
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void cooksOnManyThreadsNeverTakeTheSameItem() throws Exception {
        r.seatWalkIn(6);
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            lines.add(Line.of("STEAK", 1));
        }
        r.order("T3", lines);
        ExecutorService cooks = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<OrderItem>>> results = new ArrayList<>();
        for (int c = 0; c < 6; c++) {
            results.add(cooks.submit(() -> {
                start.await();
                List<OrderItem> mine = new ArrayList<>();
                Optional<OrderItem> next;
                while ((next = r.startNext(Station.GRILL)).isPresent()) {
                    mine.add(next.get());
                    r.markReady(next.get().id());
                }
                return mine;
            }));
        }
        start.countDown();
        List<OrderItem> all = new ArrayList<>();
        for (Future<List<OrderItem>> f : results) {
            all.addAll(f.get(10, TimeUnit.SECONDS));
        }
        cooks.shutdown();
        assertEquals(200, all.size());
        assertEquals(200, all.stream().distinct().count());
        assertTrue(r.tab("T3").items().stream().allMatch(i -> i.status() == OrderItem.Status.READY));
    }
}
