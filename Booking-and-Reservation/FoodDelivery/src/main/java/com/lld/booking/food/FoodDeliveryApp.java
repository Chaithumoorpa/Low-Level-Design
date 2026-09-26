package com.lld.booking.food;

import com.lld.booking.food.dispatch.AssignmentStrategy;
import com.lld.booking.food.model.Customer;
import com.lld.booking.food.model.DeliveryPartner;
import com.lld.booking.food.model.FoodException;
import com.lld.booking.food.model.Location;
import com.lld.booking.food.model.MenuItem;
import com.lld.booking.food.model.Order;
import com.lld.booking.food.model.Promo;
import com.lld.booking.food.model.Restaurant;
import com.lld.booking.food.pricing.FeeCalculator;
import com.lld.booking.food.service.FakePayments;
import com.lld.booking.food.service.FoodDeliveryService;
import com.lld.booking.food.service.ManualClock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Supplier;

/** A lunch rush with two restaurants and two couriers, on a simulated clock (UTC). */
public class FoodDeliveryApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2027-10-04T12:00:00Z"));
        FakePayments payments = new FakePayments();
        FoodDeliveryService app = new FoodDeliveryService(AssignmentStrategy.nearest(), FeeCalculator.standard(), payments, clock);
        app.onStatusChange((o, s) -> System.out.println("   [push to " + o.customer().name() + "] " + o.id() + " " + s));

        app.addRestaurant(new Restaurant("r1", "Tandoor House", new Location(0, 0), 20, LocalTime.of(11, 0),
                LocalTime.of(23, 0), 10_00, List.of(new MenuItem("naan", "Garlic naan", 3_50),
                new MenuItem("curry", "Paneer curry", 12_00), new MenuItem("lassi", "Mango lassi", 4_00))));
        app.addRestaurant(new Restaurant("r2", "Sushi Bar", new Location(4, 3), 15, LocalTime.of(17, 0),
                LocalTime.of(22, 0), 15_00, List.of(new MenuItem("maki", "Salmon maki", 9_00))));
        app.addCustomer(new Customer("ana", "Ana", new Location(3, 4)));
        app.addCustomer(new Customer("raj", "Raj", new Location(1, 1)));
        app.addCustomer(new Customer("far", "Faraway Fay", new Location(20, 20)));
        app.addPromo(new Promo("LUNCH20", 20, 5_00, 20_00));
        app.addPartner(new DeliveryPartner("p1", "Paulo", new Location(1, 0)));
        app.addPartner(new DeliveryPartner("p2", "Mei", new Location(5, 5)));
        app.goOnline("p1", new Location(1, 0));
        app.goOnline("p2", new Location(5, 5));

        step("Cart rules");
        app.addToCart("ana", "r1", "curry", 2, false);
        app.addToCart("ana", "r1", "naan", 2, false);
        attempt(() -> {
            app.addToCart("ana", "r2", "maki", 1, false);
            return "ok";
        });
        System.out.println("   quote with LUNCH20: " + app.quote("ana", "LUNCH20"));

        step("Ana orders; the restaurant accepts; the nearest courier is assigned");
        Order a = app.placeOrder("ana", "LUNCH20");
        app.accept("r1", a.id());
        System.out.println("   " + a + ", ETA " + app.etaMinutes(a.id()) + " min");

        step("Raj orders too; Mei declines; nobody else is free, so the order waits");
        app.addToCart("raj", "r1", "curry", 1, false);
        app.addToCart("raj", "r1", "lassi", 1, false);
        Order r = app.placeOrder("raj", null);
        app.accept("r1", r.id());
        System.out.println("   assigned to " + r.partner());
        app.decline("p2", r.id());
        System.out.println("   after Mei declines: " + r + ", waiting: " + app.waitingForCourier());

        step("The kitchen cooks, Paulo delivers Ana's order and then picks up Raj's");
        app.startPreparing("r1", a.id());
        attempt(() -> app.cancel("ana", a.id()));
        clock.advance(Duration.ofMinutes(20));
        app.markReady("r1", a.id());
        app.pickUp("p1", a.id());
        clock.advance(Duration.ofMinutes(15));
        app.deliver("p1", a.id());
        System.out.println("   Raj's order now: " + r + ", ETA " + app.etaMinutes(r.id()) + " min");
        app.rate("ana", a.id(), 5, 4);

        step("Refused orders");
        app.addToCart("far", "r1", "curry", 1, false);
        attempt(() -> app.placeOrder("far", null));
        app.addToCart("raj", "r2", "maki", 2, true);
        attempt(() -> app.placeOrder("raj", null));
        app.setItemAvailable("r1", "lassi", false);
        app.addToCart("ana", "r1", "lassi", 3, false);
        attempt(() -> app.placeOrder("ana", null));

        step("Raj cancels before cooking starts: full refund, Paulo is freed");
        System.out.println("   " + app.cancel("raj", r.id()) + "; Paulo is " + app.partner("p1").status());
        System.out.println("   payments net: " + String.format("$%.2f", payments.netCollected() / 100.0));
        System.out.println("   history of " + a.id() + ": " + a.history());
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (FoodException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
