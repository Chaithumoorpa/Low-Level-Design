package com.lld.booking.food.service;

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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Facade for customers (cart, checkout, cancel, rate), restaurants (accept, prepare, ready) and couriers
 * (online, decline, pick up, deliver).
 *
 * <p><b>Dispatch.</b> When a restaurant accepts an order, the {@link AssignmentStrategy} picks an idle
 * courier. If none is free the order joins a FIFO waiting queue and gets the next courier who becomes
 * free. A courier who declines is never offered that order again.
 *
 * <p>One lock for all state: dispatch decisions need a consistent view of every courier, and this keeps
 * "one courier, one order" trivially true.
 */
public final class FoodDeliveryService {

    public static final double MAX_DISTANCE_KM = 10;
    public static final double COURIER_SPEED_KMH = 20;

    private final Map<String, Restaurant> restaurants = new LinkedHashMap<>();
    private final Map<String, Customer> customers = new HashMap<>();
    private final Map<String, DeliveryPartner> partners = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> carts = new HashMap<>();       // customer -> item -> qty
    private final Map<String, String> cartRestaurant = new HashMap<>();
    private final Map<String, Promo> promos = new HashMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Deque<Order> waitingForCourier = new ArrayDeque<>();
    private final List<BiConsumer<Order, OrderStatus>> listeners = new CopyOnWriteArrayList<>();
    private final AssignmentStrategy dispatch;
    private final FeeCalculator fees;
    private final PaymentPort payments;
    private final Clock clock;
    private long orderSeq;

    public FoodDeliveryService(AssignmentStrategy dispatch, FeeCalculator fees, PaymentPort payments, Clock clock) {
        this.dispatch = Objects.requireNonNull(dispatch);
        this.fees = Objects.requireNonNull(fees);
        this.payments = Objects.requireNonNull(payments);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Observer: push notifications and live tracking screens. */
    public void onStatusChange(BiConsumer<Order, OrderStatus> listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ setup

    public synchronized void addRestaurant(Restaurant r) {
        restaurants.put(r.id(), r);
    }

    public synchronized void addCustomer(Customer c) {
        customers.put(c.id(), c);
    }

    public synchronized void addPromo(Promo p) {
        promos.put(p.code(), p);
    }

    public synchronized void addPartner(DeliveryPartner p) {
        partners.put(p.id(), p);
    }

    public synchronized void setItemAvailable(String restaurantId, String itemId, boolean available) {
        restaurant(restaurantId).setAvailable(itemId, available);
    }

    public synchronized void pauseRestaurant(String restaurantId, boolean paused) {
        restaurant(restaurantId).setPaused(paused);
    }

    // ------------------------------------------------------------------ couriers

    /** Going online also serves the oldest order waiting for a courier. */
    public synchronized void goOnline(String partnerId, Location at) {
        DeliveryPartner p = partner(partnerId);
        if (p.status() == DeliveryPartner.Status.BUSY) {
            throw new FoodException(p.name() + " is on a delivery");
        }
        p.moveTo(at);
        p.setStatus(DeliveryPartner.Status.AVAILABLE);
        serveWaitingOrders();
    }

    public synchronized void goOffline(String partnerId) {
        DeliveryPartner p = partner(partnerId);
        if (p.status() == DeliveryPartner.Status.BUSY) {
            throw new FoodException(p.name() + " must finish the delivery first");
        }
        p.setStatus(DeliveryPartner.Status.OFFLINE);
    }

    public synchronized void updateLocation(String partnerId, Location at) {
        partner(partnerId).moveTo(at);
    }

    // ------------------------------------------------------------------ cart & checkout

    /** A cart holds one restaurant's items; adding from another restaurant needs {@code replaceCart}. */
    public synchronized void addToCart(String customerId, String restaurantId, String itemId, int qty, boolean replaceCart) {
        customer(customerId);
        Restaurant r = restaurant(restaurantId);
        if (r.item(itemId).isEmpty()) {
            throw new FoodException("No item " + itemId + " at " + r.name());
        }
        if (qty <= 0) {
            throw new FoodException("Quantity must be positive");
        }
        String current = cartRestaurant.get(customerId);
        if (current != null && !current.equals(restaurantId)) {
            if (!replaceCart) {
                throw new FoodException("Your cart has items from " + restaurant(current).name() + "; replace it?");
            }
            carts.remove(customerId);
        }
        cartRestaurant.put(customerId, restaurantId);
        carts.computeIfAbsent(customerId, k -> new LinkedHashMap<>()).merge(itemId, qty, Integer::sum);
    }

    public synchronized Bill quote(String customerId, String promoCode) {
        Customer c = customer(customerId);
        Restaurant r = restaurant(requireCart(customerId));
        return fees.bill(subtotal(customerId, r), r.location().distanceTo(c.address()), promo(promoCode));
    }

    public synchronized Order placeOrder(String customerId, String promoCode) {
        Customer c = customer(customerId);
        Restaurant r = restaurant(requireCart(customerId));
        LocalTime local = LocalTime.ofInstant(now(), ZoneOffset.UTC);
        if (!r.isOpenAt(local)) {
            throw new FoodException(r.name() + " is not taking orders right now");
        }
        double distance = r.location().distanceTo(c.address());
        if (distance > MAX_DISTANCE_KM) {
            throw new FoodException(r.name() + " doesn't deliver that far (" + String.format("%.1f", distance) + " km)");
        }
        List<Order.Line> lines = new ArrayList<>();
        carts.get(customerId).forEach((itemId, qty) -> {
            if (!r.available(itemId)) {
                throw new FoodException(r.item(itemId).map(MenuItem::name).orElse(itemId) + " is sold out");
            }
            MenuItem m = r.item(itemId).orElseThrow();
            lines.add(new Order.Line(itemId, m.name(), qty, m.priceCents()));
        });
        long subtotal = subtotal(customerId, r);
        if (subtotal < r.minOrderCents()) {
            throw new FoodException(r.name() + " has a minimum order of " + money(r.minOrderCents()));
        }
        Bill bill = fees.bill(subtotal, distance, promo(promoCode));
        String reference;
        try {
            reference = payments.charge(customerId, bill.total(), "order at " + r.name());
        } catch (PaymentPort.Declined e) {
            throw new FoodException("Payment declined");
        }
        Order o = new Order("O" + (++orderSeq), c, r, lines, bill, reference, now());
        orders.put(o.id(), o);
        carts.remove(customerId);
        cartRestaurant.remove(customerId);
        notify(o);
        return o;
    }

    // ------------------------------------------------------------------ restaurant side

    public synchronized void accept(String restaurantId, String orderId) {
        Order o = orderAt(restaurantId, orderId);
        o.move(OrderStatus.ACCEPTED, now());
        notify(o);
        assignCourier(o);
    }

    public synchronized void reject(String restaurantId, String orderId, String reason) {
        Order o = orderAt(restaurantId, orderId);
        o.move(OrderStatus.REJECTED, now());
        payments.refund(o.paymentReference(), o.bill().total());
        notify(o);
    }

    public synchronized void startPreparing(String restaurantId, String orderId) {
        Order o = orderAt(restaurantId, orderId);
        o.move(OrderStatus.PREPARING, now());
        notify(o);
    }

    public synchronized void markReady(String restaurantId, String orderId) {
        Order o = orderAt(restaurantId, orderId);
        o.move(OrderStatus.READY_FOR_PICKUP, now());
        notify(o);
    }

    // ------------------------------------------------------------------ courier side

    /** The courier refuses; the order goes to the next best courier (or waits). */
    public synchronized void decline(String partnerId, String orderId) {
        Order o = order(orderId);
        DeliveryPartner p = partner(partnerId);
        if (o.partner() != p || o.status().ordinal() >= OrderStatus.PICKED_UP.ordinal()) {
            throw new FoodException(orderId + " is not waiting for " + p.name());
        }
        o.declinedBy(partnerId);
        o.assign(null, now());
        p.setStatus(DeliveryPartner.Status.AVAILABLE);
        assignCourier(o);
        serveWaitingOrders();                                             // p may suit another waiting order
    }

    public synchronized void pickUp(String partnerId, String orderId) {
        Order o = assignedTo(partnerId, orderId);
        o.move(OrderStatus.PICKED_UP, now());
        o.partner().moveTo(o.restaurant().location());
        notify(o);
    }

    public synchronized void deliver(String partnerId, String orderId) {
        Order o = assignedTo(partnerId, orderId);
        o.move(OrderStatus.DELIVERED, now());
        DeliveryPartner p = o.partner();
        p.moveTo(o.customer().address());
        p.setStatus(DeliveryPartner.Status.AVAILABLE);
        notify(o);
        serveWaitingOrders();
    }

    // ------------------------------------------------------------------ customer after checkout

    /** Free cancellation until the kitchen starts cooking. */
    public synchronized Order cancel(String customerId, String orderId) {
        Order o = order(orderId);
        if (!o.customer().id().equals(customerId)) {
            throw new FoodException("Not your order");
        }
        if (o.status() != OrderStatus.PLACED && o.status() != OrderStatus.ACCEPTED) {
            throw new FoodException("The kitchen already started on " + orderId + "; it can't be cancelled");
        }
        o.move(OrderStatus.CANCELLED, now());
        waitingForCourier.remove(o);
        if (o.partner() != null) {
            o.partner().setStatus(DeliveryPartner.Status.AVAILABLE);
            o.assign(null, now());
            serveWaitingOrders();
        }
        payments.refund(o.paymentReference(), o.bill().total());
        notify(o);
        return o;
    }

    public synchronized void rate(String customerId, String orderId, int restaurantStars, int courierStars) {
        Order o = order(orderId);
        if (!o.customer().id().equals(customerId) || o.status() != OrderStatus.DELIVERED) {
            throw new FoodException("Only delivered orders can be rated by their customer");
        }
        if (o.rated()) {
            throw new FoodException(orderId + " is already rated");
        }
        o.restaurant().rating().add(restaurantStars);
        o.partner().rating().add(courierStars);
        o.markRated();
    }

    /**
     * Minutes until the food arrives: remaining kitchen time, then the courier's trip to the restaurant
     * (they wait if they arrive early), then the ride to the customer.
     */
    public synchronized long etaMinutes(String orderId) {
        Order o = order(orderId);
        if (o.status().isFinal()) {
            return 0;
        }
        double toCustomer = o.restaurant().location().distanceTo(o.customer().address()) / COURIER_SPEED_KMH * 60;
        if (o.status() == OrderStatus.PICKED_UP) {
            return Math.round(o.partner().location().distanceTo(o.customer().address()) / COURIER_SPEED_KMH * 60);
        }
        long kitchenLeft = 0;
        if (o.status() != OrderStatus.READY_FOR_PICKUP) {
            Instant start = o.acceptedAt() == null ? now() : o.acceptedAt();
            kitchenLeft = Math.max(0, Duration.between(now(), start.plus(Duration.ofMinutes(o.restaurant().prepMinutes()))).toMinutes());
        }
        double courierToRestaurant = o.partner() == null ? 0
                : o.partner().location().distanceTo(o.restaurant().location()) / COURIER_SPEED_KMH * 60;
        return Math.round(Math.max(kitchenLeft, courierToRestaurant) + toCustomer);
    }

    // ------------------------------------------------------------------ queries

    public synchronized Order order(String id) {
        Order o = orders.get(id);
        if (o == null) {
            throw new FoodException("No order " + id);
        }
        return o;
    }

    public synchronized List<Order> waitingForCourier() {
        return List.copyOf(waitingForCourier);
    }

    public synchronized DeliveryPartner partner(String id) {
        DeliveryPartner p = partners.get(id);
        if (p == null) {
            throw new FoodException("No courier " + id);
        }
        return p;
    }

    // ------------------------------------------------------------------ internals

    private void assignCourier(Order o) {
        List<DeliveryPartner> idle = partners.values().stream()
                .filter(p -> p.status() == DeliveryPartner.Status.AVAILABLE && !o.declinedBy().contains(p.id()))
                .toList();
        Optional<DeliveryPartner> chosen = dispatch.choose(o, idle);
        if (chosen.isPresent()) {
            chosen.get().setStatus(DeliveryPartner.Status.BUSY);
            o.assign(chosen.get(), now());
            waitingForCourier.remove(o);
        } else if (!waitingForCourier.contains(o)) {
            waitingForCourier.addLast(o);
        }
    }

    private void serveWaitingOrders() {
        for (Order o : List.copyOf(waitingForCourier)) {
            if (o.partner() == null && !o.status().isFinal()) {
                assignCourier(o);
            }
        }
    }

    private long subtotal(String customerId, Restaurant r) {
        return carts.get(customerId).entrySet().stream()
                .mapToLong(e -> r.item(e.getKey()).orElseThrow().priceCents() * e.getValue()).sum();
    }

    private String requireCart(String customerId) {
        String r = cartRestaurant.get(customerId);
        if (r == null || carts.getOrDefault(customerId, Map.of()).isEmpty()) {
            throw new FoodException("Your cart is empty");
        }
        return r;
    }

    private Promo promo(String code) {
        if (code == null) {
            return null;
        }
        Promo p = promos.get(code);
        if (p == null) {
            throw new FoodException("Unknown promo " + code);
        }
        return p;
    }

    private Order orderAt(String restaurantId, String orderId) {
        Order o = order(orderId);
        if (!o.restaurant().id().equals(restaurantId)) {
            throw new FoodException(orderId + " is not for " + restaurantId);
        }
        return o;
    }

    private Order assignedTo(String partnerId, String orderId) {
        Order o = order(orderId);
        if (o.partner() == null || !o.partner().id().equals(partnerId)) {
            throw new FoodException(orderId + " is not assigned to " + partnerId);
        }
        return o;
    }

    private Restaurant restaurant(String id) {
        Restaurant r = restaurants.get(id);
        if (r == null) {
            throw new FoodException("No restaurant " + id);
        }
        return r;
    }

    private Customer customer(String id) {
        Customer c = customers.get(id);
        if (c == null) {
            throw new FoodException("No customer " + id);
        }
        return c;
    }

    private void notify(Order o) {
        listeners.forEach(l -> l.accept(o, o.status()));
    }

    private Instant now() {
        return clock.instant();
    }

    private static String money(long c) {
        return String.format("$%d.%02d", c / 100, c % 100);
    }
}
