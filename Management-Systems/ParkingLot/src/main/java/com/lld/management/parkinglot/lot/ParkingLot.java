package com.lld.management.parkinglot.lot;

import com.lld.management.parkinglot.allocation.SpotAllocationStrategy;
import com.lld.management.parkinglot.model.ParkingException;
import com.lld.management.parkinglot.model.ParkingSpot;
import com.lld.management.parkinglot.model.Receipt;
import com.lld.management.parkinglot.model.SpotSize;
import com.lld.management.parkinglot.model.Ticket;
import com.lld.management.parkinglot.model.Vehicle;
import com.lld.management.parkinglot.model.VehicleType;
import com.lld.management.parkinglot.payment.PaymentMethod;
import com.lld.management.parkinglot.pricing.PricingStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Facade for the whole garage: entry gates call {@link #enter}, exit gates call {@link #exit}.
 *
 * <p><b>Concurrency:</b> several gates run at once. Picking a spot and marking it occupied must be one
 * atomic step, or two cars could be sent to the same bay, so allocation and release happen under one
 * lock. That critical section is tiny (a few sorted-set operations). Payment, which can be slow,
 * happens <em>outside</em> the lock: the ticket is first removed from the active map (so the same
 * ticket can't exit twice), and put back if the payment is declined.
 */
public final class ParkingLot {

    private final String name;
    private final List<ParkingFloor> floors;
    private final SpotAllocationStrategy allocation;
    private final PricingStrategy pricing;
    private final Clock clock;
    private final ReentrantLock allocationLock = new ReentrantLock();
    private final Map<String, Ticket> activeByTicket = new ConcurrentHashMap<>();
    private final Map<String, Ticket> activeByPlate = new ConcurrentHashMap<>();
    private final List<Receipt> receipts = new CopyOnWriteArrayList<>();
    private final List<ParkingEventListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong ticketSequence = new AtomicLong();
    private final AtomicLong revenue = new AtomicLong();

    public ParkingLot(String name, List<ParkingFloor> floors, SpotAllocationStrategy allocation,
                      PricingStrategy pricing, Clock clock) {
        if (floors.isEmpty()) {
            throw new IllegalArgumentException("A parking lot needs at least one floor");
        }
        this.name = name;
        this.floors = new ArrayList<>(floors);
        this.floors.sort((a, b) -> Integer.compare(a.level(), b.level()));
        this.allocation = Objects.requireNonNull(allocation);
        this.pricing = Objects.requireNonNull(pricing);
        this.clock = Objects.requireNonNull(clock);
    }

    public void addListener(ParkingEventListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ entry

    /** Assigns a spot and issues a ticket, or refuses (already inside / no suitable spot). */
    public Ticket enter(Vehicle vehicle, String gate) {
        Ticket ticket;
        ParkingSpot spot;
        allocationLock.lock();
        try {
            if (activeByPlate.containsKey(vehicle.licensePlate())) {
                throw new ParkingException(vehicle.licensePlate() + " is already inside");
            }
            spot = allocation.findSpot(vehicle.type(), floors).orElse(null);
            if (spot == null) {
                listeners.forEach(l -> l.onFull(vehicle.type()));
                throw new ParkingException("FULL for " + vehicle.type());
            }
            floorOf(spot).occupy(spot, vehicle);
            ticket = new Ticket("T" + ticketSequence.incrementAndGet(), vehicle, spot, gate, clock.instant());
            activeByTicket.put(ticket.id(), ticket);
            activeByPlate.put(vehicle.licensePlate(), ticket);
            notifyAvailability(spot);                          // inside the lock: signs never see stale counts
        } finally {
            allocationLock.unlock();
        }
        listeners.forEach(l -> l.onEntry(ticket));
        return ticket;
    }

    // ------------------------------------------------------------------ exit

    /** Calculates the fee, takes payment, frees the spot and returns a receipt. */
    public Receipt exit(String ticketId, PaymentMethod payment) {
        Ticket ticket = activeByTicket.remove(ticketId);       // claim: a second exit with this ticket fails
        if (ticket == null) {
            throw new ParkingException("Unknown or already used ticket " + ticketId);
        }
        return settle(ticket, payment, false);
    }

    /** Driver lost the ticket: identified by plate, charged at least the lost-ticket fee. */
    public Receipt exitWithLostTicket(String licensePlate, PaymentMethod payment) {
        Ticket ticket = activeByPlate.get(Vehicle.normalizePlate(licensePlate));
        if (ticket == null || activeByTicket.remove(ticket.id()) == null) {
            throw new ParkingException("No vehicle " + licensePlate + " inside");
        }
        return settle(ticket, payment, true);
    }

    private Receipt settle(Ticket ticket, PaymentMethod payment, boolean lost) {
        Instant now = clock.instant();
        Duration stay = Duration.between(ticket.entryTime(), now);
        VehicleType type = ticket.vehicle().type();
        long fee = pricing.fee(type, stay);
        if (lost) {
            fee = Math.max(fee, pricing.lostTicketFee(type));
        }
        String reference;
        try {
            reference = fee == 0 ? "FREE (grace period)" : payment.pay(fee);
        } catch (RuntimeException declined) {
            activeByTicket.put(ticket.id(), ticket);            // still parked; barrier stays down
            throw declined;
        }

        allocationLock.lock();
        try {
            floorOf(ticket.spot()).release(ticket.spot());
            activeByPlate.remove(ticket.vehicle().licensePlate());
            notifyAvailability(ticket.spot());
        } finally {
            allocationLock.unlock();
        }
        Receipt receipt = new Receipt(ticket, now, stay, fee, reference, lost);
        receipts.add(receipt);
        revenue.addAndGet(fee);
        listeners.forEach(l -> l.onExit(receipt));
        return receipt;
    }

    // ------------------------------------------------------------------ administration

    public void setOutOfService(String spotId, boolean outOfService) {
        allocationLock.lock();
        ParkingSpot spot;
        try {
            spot = findSpot(spotId);
            floorOf(spot).setOutOfService(spot, outOfService);
            notifyAvailability(spot);
        } finally {
            allocationLock.unlock();
        }
    }

    // ------------------------------------------------------------------ queries

    public Map<SpotSize, Integer> freeSpots() {
        allocationLock.lock();
        try {
            Map<SpotSize, Integer> total = new EnumMap<>(SpotSize.class);
            for (SpotSize s : SpotSize.values()) {
                total.put(s, floors.stream().mapToInt(f -> f.freeCount(s)).sum());
            }
            return total;
        } finally {
            allocationLock.unlock();
        }
    }

    public boolean hasSpaceFor(VehicleType type) {
        allocationLock.lock();
        try {
            return allocation.findSpot(type, floors).isPresent();
        } finally {
            allocationLock.unlock();
        }
    }

    public int parkedCount() {
        return activeByPlate.size();
    }

    public long revenueCents() {
        return revenue.get();
    }

    public List<Receipt> receipts() {
        return Collections.unmodifiableList(receipts);
    }

    public List<ParkingFloor> floors() {
        return Collections.unmodifiableList(floors);
    }

    public String name() {
        return name;
    }

    private ParkingFloor floorOf(ParkingSpot spot) {
        return floors.stream().filter(f -> f.level() == spot.floor()).findFirst().orElseThrow();
    }

    private ParkingSpot findSpot(String id) {
        return floors.stream().flatMap(f -> f.spot(id).stream()).findFirst()
                .orElseThrow(() -> new ParkingException("No spot " + id));
    }

    /** Called with the allocation lock held, so events arrive in the same order as the changes. */
    private void notifyAvailability(ParkingSpot spot) {
        int free = floorOf(spot).freeCount(spot.size());
        listeners.forEach(l -> l.onAvailabilityChange(spot.floor(), spot.size(), free));
    }
}
