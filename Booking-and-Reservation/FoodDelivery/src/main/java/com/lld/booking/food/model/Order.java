package com.lld.booking.food.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A placed order with its bill, assigned courier and status history. */
public final class Order {

    public record Line(String itemId, String name, int quantity, long unitPriceCents) {
    }

    private final String id;
    private final Customer customer;
    private final Restaurant restaurant;
    private final List<Line> lines;
    private final Bill bill;
    private final String paymentReference;
    private final Instant placedAt;
    private final List<String> history = new ArrayList<>();
    private final Set<String> declinedBy = new HashSet<>();
    private OrderStatus status = OrderStatus.PLACED;
    private DeliveryPartner partner;
    private Instant acceptedAt;
    private Instant readyAt;
    private boolean rated;

    public Order(String id, Customer customer, Restaurant restaurant, List<Line> lines, Bill bill,
                 String paymentReference, Instant placedAt) {
        this.id = id;
        this.customer = customer;
        this.restaurant = restaurant;
        this.lines = List.copyOf(lines);
        this.bill = bill;
        this.paymentReference = paymentReference;
        this.placedAt = placedAt;
        history.add(placedAt + " PLACED");
    }

    public String id() {
        return id;
    }

    public Customer customer() {
        return customer;
    }

    public Restaurant restaurant() {
        return restaurant;
    }

    public List<Line> lines() {
        return lines;
    }

    public Bill bill() {
        return bill;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public Instant placedAt() {
        return placedAt;
    }

    public OrderStatus status() {
        return status;
    }

    public DeliveryPartner partner() {
        return partner;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public Instant readyAt() {
        return readyAt;
    }

    public List<String> history() {
        return List.copyOf(history);
    }

    public Set<String> declinedBy() {
        return Set.copyOf(declinedBy);
    }

    public boolean rated() {
        return rated;
    }

    public void markRated() {
        rated = true;
    }

    public void move(OrderStatus to, Instant at) {
        if (!status.next().contains(to)) {
            throw new FoodException(id + " can't go from " + status + " to " + to);
        }
        status = to;
        if (to == OrderStatus.ACCEPTED) {
            acceptedAt = at;
        }
        if (to == OrderStatus.READY_FOR_PICKUP) {
            readyAt = at;
        }
        history.add(at + " " + to);
    }

    public void assign(DeliveryPartner p, Instant at) {
        partner = p;
        history.add(at + " courier " + (p == null ? "unassigned" : p.name()));
    }

    public void declinedBy(String partnerId) {
        declinedBy.add(partnerId);
    }

    @Override
    public String toString() {
        return id + " " + customer.name() + " from " + restaurant.name() + " " + bill.total() / 100 + "."
                + String.format("%02d", bill.total() % 100) + " [" + status + (partner == null ? "" : ", " + partner.name()) + "]";
    }
}
