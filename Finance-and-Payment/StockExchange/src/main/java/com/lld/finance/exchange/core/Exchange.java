package com.lld.finance.exchange.core;

import com.lld.finance.exchange.account.Account;
import com.lld.finance.exchange.book.OrderBook;
import com.lld.finance.exchange.model.ExchangeException;
import com.lld.finance.exchange.model.Order;
import com.lld.finance.exchange.model.OrderStatus;
import com.lld.finance.exchange.model.OrderType;
import com.lld.finance.exchange.model.Prices;
import com.lld.finance.exchange.model.Side;
import com.lld.finance.exchange.model.TimeInForce;
import com.lld.finance.exchange.model.Trade;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Facade + matching engine. One {@link OrderBook} per symbol; everything that touches a book (accept,
 * match, rest, cancel, modify) runs under that book's monitor, so each symbol has a single, strictly
 * ordered stream of events. Different symbols match in parallel.
 *
 * <p>Order of checks for a new order: validate (tick size, quantity, price band) → reserve cash or
 * shares → fill-or-kill pre-check → match against the opposite side (price-time priority, trade at the
 * resting price, self-trade prevention) → rest (GTC limit) or cancel the remainder (IOC, market).
 */
public final class Exchange {

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, List<Trade>> trades = new ConcurrentHashMap<>();
    private final List<TradeListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong orderSeq = new AtomicLong();
    private final AtomicLong tradeSeq = new AtomicLong();
    private final Clock clock;

    public Exchange(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public void addTradeListener(TradeListener l) {
        listeners.add(l);
    }

    // ------------------------------------------------------------------ setup

    /** @param priceBandBasisPoints limit orders further than this from the last trade are rejected (1000 = 10%) */
    public void listSymbol(String symbol, long tickSize, int priceBandBasisPoints) {
        if (books.putIfAbsent(symbol, new OrderBook(symbol, tickSize, priceBandBasisPoints)) != null) {
            throw new ExchangeException(symbol + " is already listed");
        }
        trades.put(symbol, new CopyOnWriteArrayList<>());
    }

    public Account openAccount(String traderId, long cash) {
        Account a = new Account(traderId, cash);
        if (accounts.putIfAbsent(traderId, a) != null) {
            throw new ExchangeException("Account " + traderId + " exists");
        }
        return a;
    }

    public void depositShares(String traderId, String symbol, long quantity) {
        book(symbol);
        account(traderId).deposit(symbol, quantity);
    }

    // ------------------------------------------------------------------ orders

    public Order limit(String traderId, String symbol, Side side, long quantity, long price, TimeInForce tif) {
        return submit(traderId, symbol, side, OrderType.LIMIT, tif, price, quantity);
    }

    public Order market(String traderId, String symbol, Side side, long quantity) {
        return submit(traderId, symbol, side, OrderType.MARKET, TimeInForce.IOC, 0, quantity);
    }

    public Order submit(String traderId, String symbol, Side side, OrderType type, TimeInForce tif,
                        long price, long quantity) {
        OrderBook book = book(symbol);
        Account account = account(traderId);
        List<Trade> executed = new ArrayList<>();
        Order order;
        synchronized (book) {
            long n = orderSeq.incrementAndGet();
            order = new Order("O" + n, traderId, symbol, side, type, tif, price, quantity, n, clock.instant());
            orders.put(order.id(), order);
            String problem = validate(book, order);
            if (problem == null) {
                problem = reserve(account, order);
            }
            if (problem != null) {
                order.reject(problem);
                return order;
            }
            if (tif == TimeInForce.FOK && book.fillableQuantity(order) < quantity) {
                release(account, order);
                order.cancel("fill-or-kill: only " + book.fillableQuantity(order) + " available");
                return order;
            }
            match(book, order, executed);
            finish(book, account, order);
        }
        executed.forEach(t -> listeners.forEach(l -> l.onTrade(t)));
        return order;
    }

    /** Cancels the rest of a live order and frees its reservation. */
    public Order cancel(String traderId, String orderId) {
        Order o = order(orderId);
        OrderBook book = book(o.symbol());
        synchronized (book) {
            requireOwnLiveOrder(traderId, o);
            book.remove(o);
            release(account(traderId), o);
            o.cancel("cancelled by trader");
        }
        return o;
    }

    /**
     * Same price and smaller quantity: amended in place, keeping its place in the queue. Anything else
     * (new price, bigger quantity) is cancel-and-replace: a new order at the back of the queue.
     */
    public Order modify(String traderId, String orderId, long newPrice, long newQuantity) {
        Order o = order(orderId);
        OrderBook book = book(o.symbol());
        synchronized (book) {
            requireOwnLiveOrder(traderId, o);
            if (newPrice == o.price() && newQuantity < o.quantity() && newQuantity > o.filled()) {
                long freed = o.quantity() - newQuantity;
                Account a = account(traderId);
                if (o.side() == Side.BUY) {
                    a.releaseCash(freed * o.price());
                } else {
                    a.releaseShares(o.symbol(), freed);
                }
                o.reduceTo(newQuantity);
                return o;
            }
            cancel(traderId, orderId);
            return submit(traderId, o.symbol(), o.side(), o.type(), o.timeInForce(), newPrice, newQuantity - o.filled());
        }
    }

    // ------------------------------------------------------------------ matching

    private void match(OrderBook book, Order in, List<Trade> executed) {
        Account inAccount = account(in.traderId());
        while (in.remaining() > 0) {
            Order resting = book.best(in.side().opposite());
            if (resting == null || !in.acceptsPrice(resting.price())) {
                return;
            }
            if (resting.traderId().equals(in.traderId())) {
                in.cancel("self-trade prevented: would trade with own " + resting.id());
                return;
            }
            long qty = Math.min(in.remaining(), resting.remaining());
            if (in.type() == OrderType.MARKET && in.side() == Side.BUY) {
                qty = Math.min(qty, inAccount.availableCash() / resting.price());   // market buys spend available cash
                if (qty == 0) {
                    in.cancel("not enough cash for more at " + Prices.format(resting.price()));
                    return;
                }
            }
            long price = resting.price();
            Order buy = in.side() == Side.BUY ? in : resting;
            Order sell = in.side() == Side.SELL ? in : resting;
            long reservedPerUnit = buy.type() == OrderType.LIMIT ? buy.price() : 0;
            account(buy.traderId()).settleBuy(book.symbol(), qty, price, reservedPerUnit);
            account(sell.traderId()).settleSell(book.symbol(), qty, price);
            in.fill(qty);
            resting.fill(qty);
            if (resting.remaining() == 0) {
                book.remove(resting);
            }
            book.setLastPrice(price);
            Trade t = new Trade(tradeSeq.incrementAndGet(), book.symbol(), price, qty, buy.id(), sell.id(),
                    buy.traderId(), sell.traderId(), in.side(), clock.instant());
            trades.get(book.symbol()).add(t);
            executed.add(t);
        }
    }

    /** After matching: rest a GTC limit remainder, otherwise cancel it; always free unused reservations. */
    private void finish(OrderBook book, Account account, Order in) {
        if (in.remaining() == 0) {
            return;
        }
        if (in.status() == OrderStatus.CANCELLED) {                // self-trade or cash ran out
            release(account, in);
            return;
        }
        if (in.type() == OrderType.LIMIT && in.timeInForce() == TimeInForce.GTC) {
            book.rest(in);
            return;
        }
        release(account, in);
        in.cancel(in.type() == OrderType.MARKET ? "no more liquidity; " + in.remaining() + " unfilled"
                : "immediate-or-cancel: " + in.remaining() + " unfilled");
    }

    private String validate(OrderBook book, Order o) {
        if (o.quantity() <= 0) {
            return "quantity must be positive";
        }
        if (o.type() == OrderType.LIMIT) {
            if (o.price() <= 0 || o.price() % book.tickSize() != 0) {
                return "price must be a positive multiple of the tick size " + Prices.format(book.tickSize());
            }
            Optional<Long> last = book.lastPrice();
            if (last.isPresent()) {
                long band = last.get() * book.priceBandBasisPoints() / 10_000;
                if (Math.abs(o.price() - last.get()) > band) {
                    return "price " + Prices.format(o.price()) + " is outside the band around last "
                            + Prices.format(last.get());
                }
            }
        } else if (o.timeInForce() == TimeInForce.GTC) {
            return "market orders can't rest on the book";
        }
        return null;
    }

    private String reserve(Account a, Order o) {
        if (o.side() == Side.SELL) {
            return a.reserveShares(o.symbol(), o.quantity()) ? null
                    : "not enough " + o.symbol() + " shares (" + a.availableShares(o.symbol()) + " available)";
        }
        if (o.type() == OrderType.LIMIT) {
            return a.reserveCash(o.quantity() * o.price()) ? null
                    : "not enough cash: needs " + Prices.format(o.quantity() * o.price()) + ", has "
                    + Prices.format(a.availableCash());
        }
        return a.availableCash() > 0 ? null : "no cash available";
    }

    private void release(Account a, Order o) {
        if (o.side() == Side.SELL) {
            a.releaseShares(o.symbol(), o.remaining());
        } else if (o.type() == OrderType.LIMIT) {
            a.releaseCash(o.remaining() * o.price());
        }
    }

    // ------------------------------------------------------------------ market data & lookups

    public Optional<Long> bestBid(String symbol) {
        OrderBook b = book(symbol);
        synchronized (b) {
            return b.bestBid();
        }
    }

    public Optional<Long> bestAsk(String symbol) {
        OrderBook b = book(symbol);
        synchronized (b) {
            return b.bestAsk();
        }
    }

    public Optional<Long> lastPrice(String symbol) {
        OrderBook b = book(symbol);
        synchronized (b) {
            return b.lastPrice();
        }
    }

    public List<OrderBook.Level> depth(String symbol, Side side, int levels) {
        OrderBook b = book(symbol);
        synchronized (b) {
            return b.depth(side, levels);
        }
    }

    public List<Order> restingOrders(String symbol, Side side) {
        OrderBook b = book(symbol);
        synchronized (b) {
            return b.orders(side);
        }
    }

    public List<Trade> trades(String symbol) {
        book(symbol);
        return List.copyOf(trades.get(symbol));
    }

    public Account account(String traderId) {
        Account a = accounts.get(traderId);
        if (a == null) {
            throw new ExchangeException("No account " + traderId);
        }
        return a;
    }

    public Order order(String orderId) {
        Order o = orders.get(orderId);
        if (o == null) {
            throw new ExchangeException("No order " + orderId);
        }
        return o;
    }

    private OrderBook book(String symbol) {
        OrderBook b = books.get(symbol);
        if (b == null) {
            throw new ExchangeException("Symbol " + symbol + " is not listed");
        }
        return b;
    }

    private static void requireOwnLiveOrder(String traderId, Order o) {
        if (!o.traderId().equals(traderId)) {
            throw new ExchangeException(o.id() + " is not your order");
        }
        if (!o.status().isLive()) {
            throw new ExchangeException(o.id() + " is " + o.status());
        }
    }
}
