package com.lld.finance.exchange.model;

import java.time.Instant;

/**
 * An order and its progress. {@code sequence} is the arrival number used for time priority: among
 * orders at the same price, the earlier one trades first. Mutated only by the matching engine under
 * the symbol's lock.
 */
public final class Order {

    private final String id;
    private final String traderId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final TimeInForce timeInForce;
    private final long price;
    private final long sequence;
    private final Instant createdAt;
    private long quantity;
    private long filled;
    private OrderStatus status = OrderStatus.OPEN;
    private String reason = "";

    public Order(String id, String traderId, String symbol, Side side, OrderType type, TimeInForce tif,
                 long price, long quantity, long sequence, Instant createdAt) {
        this.id = id;
        this.traderId = traderId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.timeInForce = tif;
        this.price = price;
        this.quantity = quantity;
        this.sequence = sequence;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String traderId() {
        return traderId;
    }

    public String symbol() {
        return symbol;
    }

    public Side side() {
        return side;
    }

    public OrderType type() {
        return type;
    }

    public TimeInForce timeInForce() {
        return timeInForce;
    }

    /** Limit price in cents (0 for market orders). */
    public long price() {
        return price;
    }

    public long sequence() {
        return sequence;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long quantity() {
        return quantity;
    }

    public long filled() {
        return filled;
    }

    public long remaining() {
        return quantity - filled;
    }

    public OrderStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    /** Would this order accept a trade at {@code p}? */
    public boolean acceptsPrice(long p) {
        return type == OrderType.MARKET || (side == Side.BUY ? p <= price : p >= price);
    }

    // ---- engine only

    public void fill(long qty) {
        filled += qty;
        status = remaining() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel(String why) {
        status = OrderStatus.CANCELLED;
        reason = why;
    }

    public void reject(String why) {
        status = OrderStatus.REJECTED;
        reason = why;
    }

    /** Quantity decrease that keeps queue position. */
    public void reduceTo(long newQuantity) {
        quantity = newQuantity;
    }

    @Override
    public String toString() {
        String px = type == OrderType.MARKET ? "MKT" : Prices.format(price);
        return String.format("%s %s %s %d %s @ %s %s filled %d/%d [%s%s]", id, traderId, side, quantity, symbol, px,
                timeInForce, filled, quantity, status, reason.isEmpty() ? "" : ": " + reason);
    }
}
