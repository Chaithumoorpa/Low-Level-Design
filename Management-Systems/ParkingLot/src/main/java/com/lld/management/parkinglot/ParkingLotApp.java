package com.lld.management.parkinglot;

import com.lld.management.parkinglot.allocation.BestFitStrategy;
import com.lld.management.parkinglot.lot.DisplayBoard;
import com.lld.management.parkinglot.lot.ManualClock;
import com.lld.management.parkinglot.lot.ParkingFloor;
import com.lld.management.parkinglot.lot.ParkingLot;
import com.lld.management.parkinglot.model.ParkingException;
import com.lld.management.parkinglot.model.SpotSize;
import com.lld.management.parkinglot.model.Ticket;
import com.lld.management.parkinglot.model.Vehicle;
import com.lld.management.parkinglot.model.VehicleType;
import com.lld.management.parkinglot.payment.CardPayment;
import com.lld.management.parkinglot.payment.CashPayment;
import com.lld.management.parkinglot.pricing.HourlyPricing;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A morning at a small two-floor garage, on a simulated clock. */
public class ParkingLotApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2026-05-04T08:00:00Z"));
        ParkingLot lot = new ParkingLot("City Centre Garage",
                List.of(floor(0, 1, 3, 1, 1), floor(1, 2, 3, 1, 1)),
                new BestFitStrategy(), HourlyPricing.standard(), clock);
        DisplayBoard board = new DisplayBoard(lot);

        System.out.println(lot.name() + ", free spaces at opening:");
        System.out.print(board.render());

        Ticket car1 = enter(lot, Vehicle.of("KA-01-AB-1234", VehicleType.CAR));
        Ticket ev = enter(lot, Vehicle.of("EV 777", VehicleType.ELECTRIC_CAR));
        Ticket bike = enter(lot, Vehicle.of("MH12 BK 42", VehicleType.MOTORCYCLE));
        Ticket truck = enter(lot, Vehicle.of("TRK-9", VehicleType.TRUCK));
        enter(lot, Vehicle.of("TRK-10", VehicleType.TRUCK));
        enter(lot, Vehicle.of("TRK-11", VehicleType.TRUCK));        // only 2 large spots
        enter(lot, Vehicle.of("KA01AB1234", VehicleType.CAR));      // same plate again

        System.out.println();
        System.out.print(board.render());

        clock.advance(Duration.ofMinutes(10));
        exit(lot, bike.id(), new CashPayment(0), "motorcycle leaves after 10 min (grace period)");
        clock.advance(Duration.ofMinutes(140));
        exit(lot, car1.id(), new CardPayment("card-4242", (card, amount) -> false), "car pays by card: declined");
        exit(lot, car1.id(), new CashPayment(1000), "car pays $10 cash after 2h30");
        exit(lot, car1.id(), new CashPayment(1000), "same ticket used again");
        clock.advance(Duration.ofHours(9));
        exit(lot, truck.id(), new CardPayment("card-1111", (card, amount) -> true), "truck after 11h30 (daily cap)");
        try {
            System.out.println("\n> EV driver lost the ticket");
            System.out.println("   " + lot.exitWithLostTicket("ev777", new CashPayment(5000)));
        } catch (ParkingException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
        if (ev != null) {
            System.out.println("   (ticket " + ev.id() + " can no longer be used)");
        }

        System.out.println();
        System.out.println("Revenue: $" + lot.revenueCents() / 100 + "." + String.format("%02d", lot.revenueCents() % 100)
                + " from " + lot.receipts().size() + " exits, " + lot.parkedCount() + " vehicles still inside");
        System.out.print(board.render());
    }

    private static ParkingFloor floor(int level, int motorcycle, int compact, int ev, int large) {
        Map<SpotSize, Integer> layout = new LinkedHashMap<>();
        layout.put(SpotSize.MOTORCYCLE, motorcycle);
        layout.put(SpotSize.COMPACT, compact);
        layout.put(SpotSize.EV, ev);
        layout.put(SpotSize.LARGE, large);
        return new ParkingFloor(level, layout);
    }

    private static Ticket enter(ParkingLot lot, Vehicle v) {
        try {
            Ticket t = lot.enter(v, "GATE-A");
            System.out.println("   IN  " + v.type() + " " + v.licensePlate() + " -> " + t.spot().id() + " (" + t.id() + ")");
            return t;
        } catch (ParkingException e) {
            System.out.println("   IN  " + v.type() + " " + v.licensePlate() + " [refused] " + e.getMessage());
            return null;
        }
    }

    private static void exit(ParkingLot lot, String ticketId, com.lld.management.parkinglot.payment.PaymentMethod pay, String title) {
        System.out.println("\n> " + title);
        try {
            System.out.println("   " + lot.exit(ticketId, pay));
        } catch (ParkingException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
