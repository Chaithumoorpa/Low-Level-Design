package com.lld.booking.food;

import com.lld.booking.food.dispatch.AssignmentStrategy;
import com.lld.booking.food.model.Bill;
import com.lld.booking.food.model.Customer;
import com.lld.booking.food.model.DeliveryPartner;
import com.lld.booking.food.model.FoodException;
import com.lld.booking.food.model.Location;
import com.lld.booking.food.model.MenuItem;
import com.lld.booking.food.model.Order;
import com.lld.booking.food.model.OrderStatus;
import com.lld.booking.food.model.Promo;
import com.lld.booking.food.model.Restaurant;
import com.lld.booking.food.pricing.FeeCalculator;
import com.lld.booking.food.service.FakePayments;
import com.lld.booking.food.service.FoodDeliveryService;
import com.lld.booking.food.service.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

class FoodDeliveryServiceTest {

    private ManualClock clock;
    private FakePayments payments;
    private FoodDeliveryService app;
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2027-10-04T12:00:00Z"));
        payments = new FakePayments();
        app = service(AssignmentStrategy.nearest());
    }

    private FoodDeliveryService service(AssignmentStrategy strategy) {
        FoodDeliveryService s = new FoodDeliveryService(strategy, FeeCalculator.standard(), payments, clock);
        s.onStatusChange((o, st) -> events.add(o.id() + " " + st));
        s.addRestaurant(new Restaurant("r1", "Tandoor", new Location(0, 0), 20, LocalTime.of(11, 0), LocalTime.of(23, 0),
                10_00, List.of(new MenuItem("curry", "Curry", 12_00), new MenuItem("naan", "Naan", 3_50))));
        s.addRestaurant(new Restaurant("r2", "Sushi", new Location(4, 3), 15, LocalTime.of(17, 0), LocalTime.of(22, 0),
                15_00, List.of(new MenuItem("maki", "Maki", 9_00))));
        s.addCustomer(new Customer("c1", "Ana", new Location(3, 4)));
        s.addCustomer(new Customer("c2", "Raj", new Location(1, 1)));
        s.addCustomer(new Customer("far", "Fay", new Location(20, 20)));
        s.addPromo(new Promo("P20", 20, 5_00, 20_00));
        return s;
    }

    private DeliveryPartner online(String id, double x, double y) {
        app.addPartner(new DeliveryPartner(id, id, new Location(x, y)));
        app.goOnline(id, new Location(x, y));
        return app.partner(id);
    }

    private Order order(String customer, String item, int qty) {
        app.addToCart(customer, "r1", item, qty, true);
        return app.placeOrder(customer, null);
    }

    // ------------------------------------------------------------------ cart & pricing

    @Nested
    class Checkout {

        @ParameterizedTest(name = "subtotal {0}c, {1} km, promo {2} -> total {3}c")
        @CsvSource({"3100,5.0,true,3079", "1200,1.5,false,1609", "1600,2.0,false,1879", "2500,2.1,true,2349",
                "1500,0,false,1774", "10000,0,true,10174"})
        void bills(long subtotal, double km, boolean promo, long total) {
            Bill b = FeeCalculator.standard().bill(subtotal, km, promo ? new Promo("P", 20, 5_00, 20_00) : null);
            assertEquals(total, b.total(), b.toString());
        }

        @Test
        void oneRestaurantPerCart() {
            app.addToCart("c1", "r1", "curry", 1, false);
            assertThrows(FoodException.class, () -> app.addToCart("c1", "r2", "maki", 1, false));
            app.addToCart("c1", "r2", "maki", 2, true);
            assertEquals(18_00, app.quote("c1", null).subtotal());
            assertThrows(FoodException.class, () -> app.addToCart("c1", "r1", "pizza", 1, true));
            assertThrows(FoodException.class, () -> app.addToCart("c1", "r1", "curry", 0, true));
        }

        @Test
        void checkoutRules() {
            assertThrows(FoodException.class, () -> app.placeOrder("c1", null), "empty cart");
            app.addToCart("c1", "r2", "maki", 2, false);
            assertThrows(FoodException.class, () -> app.placeOrder("c1", null), "sushi opens at 17:00");
            app.addToCart("c1", "r1", "naan", 2, true);
            assertThrows(FoodException.class, () -> app.placeOrder("c1", null), "minimum order $10");
            app.addToCart("far", "r1", "curry", 1, false);
            assertThrows(FoodException.class, () -> app.placeOrder("far", null), "too far");
            app.addToCart("c2", "r1", "curry", 1, false);
            app.setItemAvailable("r1", "curry", false);
            assertThrows(FoodException.class, () -> app.placeOrder("c2", null), "sold out");
            app.setItemAvailable("r1", "curry", true);
            app.pauseRestaurant("r1", true);
            assertThrows(FoodException.class, () -> app.placeOrder("c2", null), "paused");
            app.pauseRestaurant("r1", false);
            assertThrows(FoodException.class, () -> app.placeOrder("c2", "NOPE"));
            payments.decline("c2");
            assertThrows(FoodException.class, () -> app.placeOrder("c2", null));
            payments.allow("c2");
            Order o = app.placeOrder("c2", null);
            assertEquals(o.bill().total(), payments.netCollected());
            assertThrows(FoodException.class, () -> app.placeOrder("c2", null), "cart emptied after ordering");
        }
    }

    // ------------------------------------------------------------------ dispatch

    @Nested
    class Dispatch {

        @Test
        void nearestIdleCourierGetsTheOrderAtAcceptance() {
            online("far", 8, 8);
            online("near", 1, 0);
            Order o = order("c1", "curry", 1);
            assertNull(o.partner(), "no courier before the restaurant accepts");
            app.accept("r1", o.id());
            assertEquals("near", o.partner().id());
            assertEquals(DeliveryPartner.Status.BUSY, o.partner().status());
        }

        @Test
        void declinesMoveOnAndNeverComeBack() {
            online("a", 1, 0);
            online("b", 2, 0);
            Order o = order("c1", "curry", 1);
            app.accept("r1", o.id());
            app.decline("a", o.id());
            assertEquals("b", o.partner().id());
            app.decline("b", o.id());
            assertNull(o.partner());
            assertEquals(List.of(o), app.waitingForCourier(), "both declined: wait for someone new");
            online("c", 9, 9);
            assertEquals("c", o.partner().id());
            assertThrows(FoodException.class, () -> app.decline("a", o.id()));
        }

        @Test
        void waitingOrdersAreServedFirstInFirstOut() {
            DeliveryPartner p = online("p", 0, 0);
            Order first = order("c1", "curry", 1);
            Order second = order("c2", "curry", 1);
            Order third = order("c1", "curry", 2);
            app.accept("r1", first.id());
            app.accept("r1", second.id());
            app.accept("r1", third.id());
            assertEquals(List.of(second, third), app.waitingForCourier());
            app.startPreparing("r1", first.id());
            app.markReady("r1", first.id());
            app.pickUp("p", first.id());
            app.deliver("p", first.id());
            assertEquals(p, second.partner());
            assertEquals(List.of(third), app.waitingForCourier());
        }

        @Test
        void bestRatedWithinRadius() {
            app = service(AssignmentStrategy.bestRatedWithin(3));
            DeliveryPartner close = online("close", 0.5, 0);
            online("good", 2, 0);
            online("star", 5, 0);                                          // outside 3 km
            close.rating().add(3);
            app.partner("star").rating().add(5);
            Order o = order("c1", "curry", 1);
            app.accept("r1", o.id());
            assertEquals("good", o.partner().id(), "unrated counts as 5.0, beats 3.0; star is too far");
        }

        @Test
        void offlineRules() {
            online("p", 0, 0);
            Order o = order("c1", "curry", 1);
            app.accept("r1", o.id());
            assertThrows(FoodException.class, () -> app.goOffline("p"));
            assertThrows(FoodException.class, () -> app.pickUp("p", o.id()), "not ready yet");
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Nested
    class Lifecycle {

        @Test
        void fullJourneyAndEtas() {
            online("p", 1, 0);
            Order o = order("c1", "curry", 2);
            app.accept("r1", o.id());
            assertEquals(35, app.etaMinutes(o.id()), "20 min kitchen + 15 min ride (5 km at 20 km/h)");
            app.startPreparing("r1", o.id());
            clock.advance(Duration.ofMinutes(10));
            assertEquals(25, app.etaMinutes(o.id()));
            clock.advance(Duration.ofMinutes(10));
            app.markReady("r1", o.id());
            assertEquals(18, app.etaMinutes(o.id()), "3 min to the restaurant + 15 min");
            app.pickUp("p", o.id());
            assertEquals(15, app.etaMinutes(o.id()));
            app.deliver("p", o.id());
            assertEquals(0, app.etaMinutes(o.id()));
            assertEquals(List.of(o.id() + " PLACED", o.id() + " ACCEPTED", o.id() + " PREPARING", o.id() + " READY_FOR_PICKUP",
                    o.id() + " PICKED_UP", o.id() + " DELIVERED"), events);
            assertEquals(new Location(3, 4), app.partner("p").location());
        }

        @Test
        void illegalTransitions() {
            Order o = order("c1", "curry", 1);
            assertThrows(FoodException.class, () -> app.markReady("r1", o.id()));
            assertThrows(FoodException.class, () -> app.accept("r2", o.id()), "other restaurant");
            app.reject("r1", o.id(), "too busy");
            assertEquals(0, payments.netCollected(), "refunded");
            assertThrows(FoodException.class, () -> app.accept("r1", o.id()));
        }

        @Test
        void cancellationWindow() {
            DeliveryPartner p = online("p", 0, 0);
            Order a = order("c1", "curry", 1);
            app.cancel("c1", a.id());
            Order b = order("c1", "curry", 1);
            app.accept("r1", b.id());
            assertThrows(FoodException.class, () -> app.cancel("c2", b.id()), "not yours");
            app.cancel("c1", b.id());
            assertEquals(DeliveryPartner.Status.AVAILABLE, p.status());
            Order c = order("c1", "curry", 1);
            app.accept("r1", c.id());
            app.startPreparing("r1", c.id());
            assertThrows(FoodException.class, () -> app.cancel("c1", c.id()));
            assertEquals(c.bill().total(), payments.netCollected());
        }

        @Test
        void ratingOnceAfterDelivery() {
            DeliveryPartner p = online("p", 0, 0);
            Order o = order("c1", "curry", 1);
            app.accept("r1", o.id());
            assertThrows(FoodException.class, () -> app.rate("c1", o.id(), 5, 5));
            app.startPreparing("r1", o.id());
            app.markReady("r1", o.id());
            app.pickUp("p", o.id());
            app.deliver("p", o.id());
            assertThrows(FoodException.class, () -> app.rate("c1", o.id(), 6, 5));
            app.rate("c1", o.id(), 4, 2);
            assertThrows(FoodException.class, () -> app.rate("c1", o.id(), 5, 5));
            assertEquals(2.0, p.rating().average());
            assertEquals(4.0, o.restaurant().rating().average());
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void couriersAreNeverDoubleBookedUnderConcurrentAcceptance() throws Exception {
        for (int i = 0; i < 5; i++) {
            online("p" + i, i, 0);
        }
        List<Order> placed = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            app.addCustomer(new Customer("x" + i, "x" + i, new Location(1, 1)));
            app.addToCart("x" + i, "r1", "curry", 1, false);
            placed.add(app.placeOrder("x" + i, null));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Order o : placed) {
            futures.add(pool.submit(() -> {
                start.await();
                app.accept("r1", o.id());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        Set<String> busy = new HashSet<>();
        int assigned = 0;
        for (Order o : placed) {
            if (o.partner() != null) {
                assertTrue(busy.add(o.partner().id()), "courier on two orders");
                assigned++;
            }
        }
        assertEquals(5, assigned);
        assertEquals(25, app.waitingForCourier().size());
        assertTrue(placed.stream().allMatch(o -> o.status() == OrderStatus.ACCEPTED));
    }
}
