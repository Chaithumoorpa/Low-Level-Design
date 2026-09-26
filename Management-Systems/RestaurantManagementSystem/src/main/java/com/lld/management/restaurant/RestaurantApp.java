package com.lld.management.restaurant;

import com.lld.management.restaurant.billing.BillCalculator;
import com.lld.management.restaurant.billing.HappyHourRule;
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
import com.lld.management.restaurant.model.Table;
import com.lld.management.restaurant.seating.TableAssignmentStrategy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** One evening at a small bistro, on a simulated clock. */
public class RestaurantApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2026-10-09T17:30:00Z"));
        BillCalculator billing = new BillCalculator(List.of(
                new HappyHourRule(Category.DRINK, LocalTime.of(17, 0), LocalTime.of(19, 0), 5000),
                new ServiceChargeRule(6, 1000),
                new TaxRule(800)));
        Restaurant bistro = new Restaurant(TableAssignmentStrategy.smallestFit(), billing,
                Duration.ofHours(2), Duration.ofMinutes(15), clock);
        bistro.addListener(new RestaurantListener() {
            @Override
            public void onTicket(Station station, String tableId, List<OrderItem> items) {
                System.out.println("   [" + station + " screen] " + tableId + ": "
                        + items.stream().map(i -> i.quantity() + "x " + i.item().name() + (i.note().isEmpty() ? "" : " (" + i.note() + ")"))
                        .collect(Collectors.joining(", ")));
            }

            @Override
            public void onItemReady(OrderItem item) {
                System.out.println("   [handheld] pick up " + item.item().name() + " for " + item.tableId());
            }

            @Override
            public void onNoShow(Booking booking) {
                System.out.println("   [host] no-show: " + booking);
            }
        });

        bistro.addMenuItem(new MenuItem("SOUP", "Tomato soup", Category.STARTER, Station.COLD, 700));
        bistro.addMenuItem(new MenuItem("STEAK", "Steak frites", Category.MAIN, Station.GRILL, 2400));
        bistro.addMenuItem(new MenuItem("BURGER", "Bistro burger", Category.MAIN, Station.GRILL, 1600));
        bistro.addMenuItem(new MenuItem("TART", "Lemon tart", Category.DESSERT, Station.PASTRY, 800));
        bistro.addMenuItem(new MenuItem("WINE", "Glass of red", Category.DRINK, Station.BAR, 900));
        bistro.addMenuItem(new MenuItem("SODA", "Lemon soda", Category.DRINK, Station.BAR, 400));
        bistro.addTable(new Table("T1", 2));
        bistro.addTable(new Table("T2", 2));
        bistro.addTable(new Table("T3", 4));
        bistro.addTable(new Table("T4", 6));
        LocalDateTime today = bistro.now().toLocalDate().atStartOfDay();

        step("17:30 bookings");
        Booking rao = bistro.book("Rao", 6, today.withHour(18));
        Booking kim = bistro.book("Kim", 2, today.withHour(18));
        Booking silva = bistro.book("Silva", 3, today.withHour(19));
        Booking ivanova = bistro.book("Ivanova", 6, today.withHour(20));
        List.of(rao, kim, silva, ivanova).forEach(b -> System.out.println("   " + b));
        attempt(() -> bistro.book("Novak", 5, today.withHour(19)));

        step("17:30 walk-ins (T1 is booked at 18:00, T3 at 19:00, T4 at 18:00)");
        attempt(() -> bistro.seatWalkIn(2));
        attempt(() -> bistro.seatWalkIn(4));
        String walkIn = "T2";

        step("17:35 the walk-in couple orders during happy hour");
        clock.advance(Duration.ofMinutes(5));
        List<OrderItem> couple = bistro.order(walkIn, List.of(
                Line.of("WINE", 2), new Line("BURGER", 1, "no onions"), Line.of("SOUP", 1)));

        step("18:00 the party of six checks in");
        clock.advance(Duration.ofMinutes(25));
        attempt(() -> bistro.checkIn(rao.id()));
        List<OrderItem> party = bistro.order("T4", List.of(Line.of("STEAK", 4), Line.of("BURGER", 2), Line.of("WINE", 6)));
        bistro.setSoldOut("TART", true);
        attempt(() -> bistro.order("T4", List.of(Line.of("SODA", 1), Line.of("TART", 6))));

        step("Kitchen works its stations first in, first out");
        for (Station s : Station.values()) {
            bistro.startNext(s).ifPresent(i -> System.out.println("   " + s + " starts " + i));
        }
        attempt(() -> {
            bistro.cancelItem(couple.get(1).id());
            return "cancelled";
        });
        bistro.voidItem(couple.get(1).id(), "burger overcooked");
        System.out.println("   manager voided " + couple.get(1));
        cookEverything(bistro);

        step("18:20 Kim is a no-show");
        clock.advance(Duration.ofMinutes(20));
        bistro.markNoShows();

        step("Bill for " + walkIn);
        System.out.print(bistro.bill(walkIn).render());
        System.out.println("   remaining after card: " + money(bistro.pay(walkIn, bistro.bill(walkIn).totalCents())));

        step("Bill for T4 (party of 6, split six ways)");
        String bill = bistro.bill("T4").render();
        System.out.print(bill);
        List<Long> shares = BillCalculator.splitEvenly(bistro.bill("T4").totalCents(), 6);
        System.out.println("   shares: " + shares.stream().map(RestaurantApp::money).collect(Collectors.joining(" ")));
        for (long share : shares) {
            long left = bistro.pay("T4", share);
            if (left == 0) {
                System.out.println("   settled; T4 is " + bistro.table("T4").status());
            }
        }

        step("19:00 the Silvas arrive");
        clock.advance(Duration.ofMinutes(40));
        attempt(() -> bistro.checkIn(silva.id()));

        step("20:00 the Ivanovas arrive, but T4 hasn't been bussed yet");
        clock.advance(Duration.ofHours(1));
        attempt(() -> bistro.checkIn(ivanova.id()));
        bistro.markClean("T4");
        System.out.println("   T4 bussed: " + bistro.table("T4").status());
        attempt(() -> bistro.checkIn(ivanova.id()));
    }

    /** Every station finishes what it has started, then works through its queue; the floor serves it all. */
    private static void cookEverything(Restaurant bistro) {
        for (String table : List.of("T2", "T4")) {
            bistro.tab(table).items().stream().filter(i -> i.status() == OrderItem.Status.PREPARING)
                    .forEach(i -> readyAndServe(bistro, i));
        }
        for (Station s : Station.values()) {
            Optional<OrderItem> next;
            while ((next = bistro.startNext(s)).isPresent()) {
                readyAndServe(bistro, next.get());
            }
        }
    }

    private static void readyAndServe(Restaurant bistro, OrderItem item) {
        bistro.markReady(item.id());
        bistro.markServed(item.id());
    }

    private static String money(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (RestaurantException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
