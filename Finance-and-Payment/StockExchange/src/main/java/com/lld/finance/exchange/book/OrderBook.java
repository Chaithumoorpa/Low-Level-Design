package com.lld.finance.exchange.book;

import com.lld.finance.exchange.model.Order;
import com.lld.finance.exchange.model.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Resting orders of one symbol. Each side is a sorted map of <b>price levels</b>; each level is a FIFO
 * queue. Bids are sorted high → low, asks low → high, so the best price is always the first key and
 * the oldest order at that price is at the head of its queue: <b>price-time priority</b>.
 *
 * <p>Not thread-safe; the exchange calls it while holding the book's monitor.
 */
public final class OrderBook {

    /** Aggregated view of one price level (what market-data screens show). */
    public record Level(long price, long quantity, int orders) {
    }

    private final String symbol;
    private final long tickSize;
    private final int priceBandBasisPoints;
    private final TreeMap<Long, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Deque<Order>> asks = new TreeMap<>();
    private Long lastPrice;

    public OrderBook(String symbol, long tickSize, int priceBandBasisPoints) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.priceBandBasisPoints = priceBandBasisPoints;
    }

    public String symbol() {
        return symbol;
    }

    public long tickSize() {
        return tickSize;
    }

    public int priceBandBasisPoints() {
        return priceBandBasisPoints;
    }

    public Optional<Long> lastPrice() {
        return Optional.ofNullable(lastPrice);
    }

    public void setLastPrice(long price) {
        lastPrice = price;
    }

    public Optional<Long> bestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
    }

    public Optional<Long> bestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
    }

    /** Oldest order at the best price on {@code side}, or null. */
    public Order best(Side side) {
        TreeMap<Long, Deque<Order>> book = side(side);
        return book.isEmpty() ? null : book.firstEntry().getValue().peekFirst();
    }

    public void rest(Order o) {
        side(o.side()).computeIfAbsent(o.price(), p -> new ArrayDeque<>()).addLast(o);
    }

    public void remove(Order o) {
        TreeMap<Long, Deque<Order>> book = side(o.side());
        Deque<Order> level = book.get(o.price());
        if (level != null) {
            level.remove(o);
            if (level.isEmpty()) {
                book.remove(o.price());
            }
        }
    }

    /**
     * How much of {@code incoming} could trade right now (for fill-or-kill): walk the opposite side in
     * priority order while the price is acceptable, stopping at the trader's own order (self-trade
     * prevention would stop there too).
     */
    public long fillableQuantity(Order incoming) {
        long available = 0;
        for (Map.Entry<Long, Deque<Order>> level : side(incoming.side().opposite()).entrySet()) {
            if (!incoming.acceptsPrice(level.getKey())) {
                break;
            }
            for (Order resting : level.getValue()) {
                if (resting.traderId().equals(incoming.traderId())) {
                    return available;
                }
                available += resting.remaining();
                if (available >= incoming.remaining()) {
                    return available;
                }
            }
        }
        return available;
    }

    public List<Level> depth(Side side, int levels) {
        List<Level> out = new ArrayList<>();
        for (Map.Entry<Long, Deque<Order>> e : side(side).entrySet()) {
            if (out.size() == levels) {
                break;
            }
            long qty = e.getValue().stream().mapToLong(Order::remaining).sum();
            out.add(new Level(e.getKey(), qty, e.getValue().size()));
        }
        return out;
    }

    /** Resting orders on a side in priority order (for tests and inspection). */
    public List<Order> orders(Side side) {
        List<Order> out = new ArrayList<>();
        side(side).values().forEach(out::addAll);
        return out;
    }

    private TreeMap<Long, Deque<Order>> side(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}
