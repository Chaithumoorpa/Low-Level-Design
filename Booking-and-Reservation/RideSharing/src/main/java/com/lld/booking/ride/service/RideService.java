package com.lld.booking.ride.service;

import com.lld.booking.ride.geo.GridIndex;
import com.lld.booking.ride.model.Driver;
import com.lld.booking.ride.model.FareQuote;
import com.lld.booking.ride.model.Location;
import com.lld.booking.ride.model.RideException;
import com.lld.booking.ride.model.Rider;
import com.lld.booking.ride.model.Trip;
import com.lld.booking.ride.model.VehicleType;
import com.lld.booking.ride.pricing.FareCalculator;
import com.lld.booking.ride.pricing.SurgePolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Facade for riders (quote, request, cancel, rate) and drivers (online, offers, trip steps, rate).
 *
 * <p><b>Matching.</b> A request is offered to <em>one</em> driver at a time: the nearest idle driver of the
 * right product within the search radius who hasn't passed on it. The driver is OFFERED (unavailable to
 * other requests) until they accept, decline, or the offer times out; then the next driver is tried.
 * No candidate left → NO_DRIVERS.
 *
 * <p><b>Pricing.</b> The quote locks distance-based estimate and surge; the final fare uses the real
 * distance and duration with the locked surge.
 *
 * <p>One lock for all dispatch state: a driver can never be offered two trips at once.
 */
public final class RideService {

    public static final double SEARCH_RADIUS_KM = 5;
    public static final double SURGE_RADIUS_KM = 3;
    public static final Duration DEMAND_WINDOW = Duration.ofMinutes(5);
    public static final Duration QUOTE_TTL = Duration.ofMinutes(2);
    public static final Duration OFFER_TTL = Duration.ofSeconds(15);
    public static final Duration FREE_CANCEL = Duration.ofMinutes(2);
    public static final long CANCEL_FEE = 5_00;
    public static final double SPEED_KMH = 30;

    private record Demand(Instant at, Location where) {
    }

    private final Map<String, Rider> riders = new HashMap<>();
    private final Map<String, Driver> drivers = new LinkedHashMap<>();
    private final GridIndex idleDrivers = new GridIndex(1.0);
    private final Map<String, FareQuote> quotes = new HashMap<>();
    private final Map<String, Trip> trips = new LinkedHashMap<>();
    private final Map<String, Trip> activeTripByRider = new HashMap<>();
    private final Deque<Demand> demand = new ArrayDeque<>();
    private final FareCalculator fares;
    private final SurgePolicy surge;
    private final PaymentPort payments;
    private final Clock clock;
    private long seq;

    public RideService(FareCalculator fares, SurgePolicy surge, PaymentPort payments, Clock clock) {
        this.fares = Objects.requireNonNull(fares);
        this.surge = Objects.requireNonNull(surge);
        this.payments = Objects.requireNonNull(payments);
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ people

    public synchronized Rider addRider(String id, String name) {
        Rider r = new Rider(id, name);
        riders.put(id, r);
        return r;
    }

    public synchronized Driver addDriver(String id, String name, VehicleType vehicle, Location at) {
        Driver d = new Driver(id, name, vehicle, at);
        drivers.put(id, d);
        return d;
    }

    public synchronized void goOnline(String driverId, Location at) {
        Driver d = driver(driverId);
        if (d.status() != Driver.Status.OFFLINE) {
            throw new RideException(d.name() + " is already online");
        }
        d.moveTo(at);
        d.setStatus(Driver.Status.AVAILABLE);
        idleDrivers.put(d.id(), at);
    }

    public synchronized void goOffline(String driverId) {
        Driver d = driver(driverId);
        if (d.status() != Driver.Status.AVAILABLE) {
            throw new RideException(d.name() + " can't go offline while " + d.status());
        }
        d.setStatus(Driver.Status.OFFLINE);
        idleDrivers.remove(d.id());
    }

    /** GPS ping. Only idle drivers are in the search index. */
    public synchronized void updateLocation(String driverId, Location at) {
        Driver d = driver(driverId);
        d.moveTo(at);
        if (d.status() == Driver.Status.AVAILABLE) {
            idleDrivers.put(d.id(), at);
        }
    }

    // ------------------------------------------------------------------ quote & request

    public synchronized FareQuote quote(String riderId, Location pickup, Location dropoff, VehicleType vehicle) {
        rider(riderId);
        Instant now = now();
        while (!demand.isEmpty() && !demand.peekFirst().at().isAfter(now.minus(DEMAND_WINDOW))) {
            demand.pollFirst();
        }
        demand.addLast(new Demand(now, pickup));
        int requests = (int) demand.stream().filter(d -> d.where().distanceTo(pickup) <= SURGE_RADIUS_KM).count();
        int supply = idleDrivers.nearby(pickup, SURGE_RADIUS_KM,
                id -> drivers.get(id).status() == Driver.Status.AVAILABLE, Integer.MAX_VALUE).size();
        int surgeBps = surge.surgeBps(requests, supply);
        double km = pickup.distanceTo(dropoff);
        long minutes = (long) Math.ceil(km / SPEED_KMH * 60);
        FareQuote q = new FareQuote("Q" + (++seq), riderId, pickup, dropoff, vehicle, km, minutes, surgeBps,
                fares.fare(vehicle, km, minutes, surgeBps), now.plus(QUOTE_TTL));
        quotes.put(q.id(), q);
        return q;
    }

    public synchronized Trip request(String riderId, String quoteId) {
        Rider r = rider(riderId);
        FareQuote q = quotes.get(quoteId);
        if (q == null || !q.riderId().equals(riderId)) {
            throw new RideException("No quote " + quoteId + " for " + r.name());
        }
        if (!now().isBefore(q.expiresAt())) {
            throw new RideException("Quote expired; please get a new price");
        }
        if (activeTripByRider.containsKey(riderId)) {
            throw new RideException(r.name() + " already has an active trip");
        }
        Trip t = new Trip("T" + (++seq), r, q, now());
        trips.put(t.id(), t);
        activeTripByRider.put(riderId, t);
        offerNext(t);
        return t;
    }

    // ------------------------------------------------------------------ driver responses

    public synchronized void respond(String driverId, String tripId, boolean accept) {
        Trip t = trip(tripId);
        Driver d = driver(driverId);
        if (t.status() != Trip.Status.MATCHING || t.offeredTo() != d) {
            throw new RideException("No open offer of " + tripId + " for " + d.name());
        }
        if (!now().isBefore(t.offerExpiresAt())) {
            passOn(t, d, "offer timed out");
            throw new RideException("The offer expired");
        }
        if (accept) {
            d.setStatus(Driver.Status.ON_TRIP);
            idleDrivers.remove(d.id());
            t.offer(null, null);
            t.setDriver(d);
            t.move(Trip.Status.DRIVER_ASSIGNED, now(), d.name() + ", " + etaMinutes(d.location(), t.quote().pickup()) + " min away");
        } else {
            passOn(t, d, "declined");
        }
    }

    /** Scheduler job: unanswered offers pass to the next driver. @return offers expired */
    public synchronized int expireOffers() {
        int n = 0;
        for (Trip t : List.copyOf(trips.values())) {
            if (t.status() == Trip.Status.MATCHING && t.offeredTo() != null && !now().isBefore(t.offerExpiresAt())) {
                passOn(t, t.offeredTo(), "offer timed out");
                n++;
            }
        }
        return n;
    }

    public synchronized void arrived(String driverId, String tripId) {
        Trip t = driversTrip(driverId, tripId);
        t.driver().moveTo(t.quote().pickup());
        t.move(Trip.Status.DRIVER_ARRIVED, now(), "");
    }

    public synchronized void start(String driverId, String tripId) {
        driversTrip(driverId, tripId).move(Trip.Status.IN_PROGRESS, now(), "");
    }

    /** Final fare from the real distance and time, with the surge locked at quote time. */
    public synchronized Trip complete(String driverId, String tripId, double actualKm) {
        Trip t = driversTrip(driverId, tripId);
        long minutes = (long) Math.ceil(Duration.between(t.startedAt(), now()).toSeconds() / 60.0);
        long fare = fares.fare(t.quote().vehicle(), actualKm, minutes, t.quote().surgeBps());
        t.move(Trip.Status.COMPLETED, now(), String.format("%.1f km, %d min", actualKm, minutes));
        t.setFare(fare);
        payments.charge(t.rider().id(), fare, "trip " + t.id());
        release(t.driver(), t.quote().dropoff());
        activeTripByRider.remove(t.rider().id());
        return t;
    }

    // ------------------------------------------------------------------ cancellations

    /** Free while matching or within 2 minutes of assignment; later, a fee compensates the driver. */
    public synchronized Trip cancelByRider(String riderId, String tripId) {
        Trip t = trip(tripId);
        if (!t.rider().id().equals(riderId)) {
            throw new RideException("Not your trip");
        }
        if (t.status() == Trip.Status.IN_PROGRESS) {
            throw new RideException("The trip has started; ask the driver to end it");
        }
        if (t.status().isFinal()) {
            throw new RideException(tripId + " is " + t.status());
        }
        if (t.status() == Trip.Status.MATCHING) {
            if (t.offeredTo() != null) {
                release(t.offeredTo(), t.offeredTo().location());
                t.offer(null, null);
            }
        } else {
            if (now().isAfter(t.assignedAt().plus(FREE_CANCEL))) {
                t.setCancellationFee(CANCEL_FEE);
                payments.charge(riderId, CANCEL_FEE, "cancellation " + tripId);
            }
            release(t.driver(), t.driver().location());
        }
        t.move(Trip.Status.CANCELLED, now(), "by rider");
        activeTripByRider.remove(riderId);
        return t;
    }

    /** The driver drops an accepted trip before pickup: the rider goes back to matching. */
    public synchronized void cancelByDriver(String driverId, String tripId) {
        Trip t = driversTrip(driverId, tripId);
        if (t.status() != Trip.Status.DRIVER_ASSIGNED) {
            throw new RideException("Only an assigned trip can be dropped before arrival");
        }
        Driver d = t.driver();
        t.setDriver(null);
        t.passOn(d.id());
        release(d, d.location());
        t.move(Trip.Status.MATCHING, now(), d.name() + " cancelled");
        offerNext(t);
    }

    // ------------------------------------------------------------------ ratings

    public synchronized void rateDriver(String riderId, String tripId, int stars) {
        Trip t = completedTrip(tripId);
        if (!t.rider().id().equals(riderId) || t.driverRated()) {
            throw new RideException("Can't rate this driver");
        }
        t.driver().rating().add(stars);
        t.markDriverRated();
    }

    public synchronized void rateRider(String driverId, String tripId, int stars) {
        Trip t = completedTrip(tripId);
        if (!t.driver().id().equals(driverId) || t.riderRated()) {
            throw new RideException("Can't rate this rider");
        }
        t.rider().rating().add(stars);
        t.markRiderRated();
    }

    // ------------------------------------------------------------------ queries

    public synchronized Trip trip(String id) {
        Trip t = trips.get(id);
        if (t == null) {
            throw new RideException("No trip " + id);
        }
        return t;
    }

    public synchronized Driver driver(String id) {
        Driver d = drivers.get(id);
        if (d == null) {
            throw new RideException("No driver " + id);
        }
        return d;
    }

    public synchronized Optional<Trip> activeTrip(String riderId) {
        return Optional.ofNullable(activeTripByRider.get(riderId));
    }

    public synchronized List<String> idleDriversNear(Location at, double radiusKm) {
        return idleDrivers.nearby(at, radiusKm, id -> true, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------ internals

    private void offerNext(Trip t) {
        List<String> next = idleDrivers.nearby(t.quote().pickup(), SEARCH_RADIUS_KM, id -> {
            Driver d = drivers.get(id);
            return d.status() == Driver.Status.AVAILABLE && d.vehicle() == t.quote().vehicle() && !t.passedOn().contains(id);
        }, 1);
        if (next.isEmpty()) {
            t.offer(null, null);
            t.move(Trip.Status.NO_DRIVERS, now(), "nobody left within " + SEARCH_RADIUS_KM + " km");
            activeTripByRider.remove(t.rider().id());
            return;
        }
        Driver d = drivers.get(next.get(0));
        d.setStatus(Driver.Status.OFFERED);
        t.offer(d, now().plus(OFFER_TTL));
    }

    private void passOn(Trip t, Driver d, String why) {
        t.passOn(d.id());
        d.setStatus(Driver.Status.AVAILABLE);
        t.offer(null, null);
        offerNext(t);
    }

    private void release(Driver d, Location at) {
        d.moveTo(at);
        d.setStatus(Driver.Status.AVAILABLE);
        idleDrivers.put(d.id(), at);
    }

    private Trip driversTrip(String driverId, String tripId) {
        Trip t = trip(tripId);
        if (t.driver() == null || !t.driver().id().equals(driverId)) {
            throw new RideException(tripId + " is not assigned to " + driverId);
        }
        return t;
    }

    private Trip completedTrip(String tripId) {
        Trip t = trip(tripId);
        if (t.status() != Trip.Status.COMPLETED) {
            throw new RideException("Only completed trips can be rated");
        }
        return t;
    }

    private Rider rider(String id) {
        Rider r = riders.get(id);
        if (r == null) {
            throw new RideException("No rider " + id);
        }
        return r;
    }

    private static long etaMinutes(Location from, Location to) {
        return (long) Math.ceil(from.distanceTo(to) / SPEED_KMH * 60);
    }

    private Instant now() {
        return clock.instant();
    }
}
