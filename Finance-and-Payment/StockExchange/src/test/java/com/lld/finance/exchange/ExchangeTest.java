package com.lld.finance.exchange;

import com.lld.finance.exchange.account.Account;
import com.lld.finance.exchange.book.OrderBook;
import com.lld.finance.exchange.core.Exchange;
import com.lld.finance.exchange.model.ExchangeException;
import com.lld.finance.exchange.model.Order;
import com.lld.finance.exchange.model.OrderStatus;
import com.lld.finance.exchange.model.OrderType;
import com.lld.finance.exchange.model.Side;
import com.lld.finance.exchange.model.TimeInForce;
import com.lld.finance.exchange.model.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeTest {

    private static final List<String> TRADERS = List.of("a", "b", "c", "d");
    private static final long CASH = 1_000_000_00;
    private static final long SHARES = 10_000;

    private Exchange x;
    private final List<Trade> feed = new ArrayList<>();
    private final List<String> everyone = new ArrayList<>(TRADERS);
    private long totalCash = CASH * TRADERS.size();

    /** Extra accounts opened by a test are included in the conservation checks. */
    private void openExtra(String trader, long cash) {
        x.openAccount(trader, cash);
        everyone.add(trader);
        totalCash += cash;
    }

    @BeforeEach
    void setUp() {
        x = new Exchange(Clock.fixed(Instant.parse("2027-05-03T14:30:00Z"), ZoneOffset.UTC));
        x.listSymbol("S", 5, 5000);                                   // tick 0.05, band ±50%
        x.listSymbol("T", 1, 5000);
        x.addTradeListener(feed::add);
        for (String t : TRADERS) {
            x.openAccount(t, CASH);
            x.depositShares(t, "S", SHARES);
            x.depositShares(t, "T", SHARES);
        }
    }

    /** After every test: nothing created or destroyed, reservations match open orders, book not crossed. */
    @AfterEach
    void invariants() {
        checkInvariants();
    }

    private void checkInvariants() {
        long cash = 0;
        Map<String, Long> shares = new HashMap<>();
        for (String t : everyone) {
            Account a = x.account(t);
            cash += a.cash();
            shares.merge("S", a.shares("S"), Long::sum);
            shares.merge("T", a.shares("T"), Long::sum);
            assertTrue(a.availableCash() >= 0 && a.availableShares("S") >= 0, t + " over-committed");
            long expectedCash = 0;
            long expectedS = 0;
            for (String sym : List.of("S", "T")) {
                for (Order o : x.restingOrders(sym, Side.BUY)) {
                    if (o.traderId().equals(t)) {
                        expectedCash += o.remaining() * o.price();
                    }
                }
                for (Order o : x.restingOrders(sym, Side.SELL)) {
                    if (o.traderId().equals(t) && sym.equals("S")) {
                        expectedS += o.remaining();
                    }
                }
            }
            assertEquals(expectedCash, a.reservedCash(), "reserved cash of " + t);
            assertEquals(expectedS, a.shares("S") - a.availableShares("S"), "reserved S shares of " + t);
        }
        assertEquals(totalCash, cash, "cash conserved");
        assertEquals(SHARES * TRADERS.size(), shares.get("S"), "shares conserved");
        for (String sym : List.of("S", "T")) {
            if (x.bestBid(sym).isPresent() && x.bestAsk(sym).isPresent()) {
                assertTrue(x.bestBid(sym).get() < x.bestAsk(sym).get(), sym + " book left crossed");
            }
        }
    }

    private Order buy(String t, long qty, long price) {
        return x.limit(t, "S", Side.BUY, qty, price, TimeInForce.GTC);
    }

    private Order sell(String t, long qty, long price) {
        return x.limit(t, "S", Side.SELL, qty, price, TimeInForce.GTC);
    }

    // ------------------------------------------------------------------ matching

    @Nested
    class Matching {

        @Test
        void priceThenTimePriorityAndTradesAtTheRestingPrice() {
            Order a1 = sell("a", 100, 101_00);
            Order b1 = sell("b", 100, 100_50);                          // better price, arrives later
            Order c1 = sell("c", 100, 100_50);                          // same price, even later
            Order buy = buy("d", 250, 102_00);
            assertEquals(OrderStatus.FILLED, buy.status());
            assertEquals(List.of(b1.id(), c1.id(), a1.id()), feed.stream().map(Trade::sellOrderId).toList());
            assertEquals(List.of(100_50L, 100_50L, 101_00L), feed.stream().map(Trade::price).toList(),
                    "the buyer's 102.00 limit is never the price: resting orders set it");
            assertEquals(50, a1.remaining());
            assertEquals(101_00L, x.lastPrice("S").orElseThrow());
            long paid = 100 * 100_50 * 2 + 50 * 101_00;
            assertEquals(CASH - paid, x.account("d").cash());
            assertEquals(CASH - paid, x.account("d").availableCash(), "limit-price surplus released");
        }

        @Test
        void nonCrossingOrdersRestAndPartialFillsRest() {
            buy("a", 100, 99_00);
            sell("b", 100, 100_00);
            assertEquals(List.of(), feed);
            Order partial = buy("c", 150, 100_00);
            assertEquals(OrderStatus.PARTIALLY_FILLED, partial.status());
            assertEquals(List.of(new OrderBook.Level(100_00, 50, 1), new OrderBook.Level(99_00, 100, 1)),
                    x.depth("S", Side.BUY, 5));
        }

        @Test
        void sellAggressorHitsTheHighestBid() {
            buy("a", 100, 99_00);
            buy("b", 100, 99_50);
            Order s = sell("c", 150, 98_00);
            assertEquals(OrderStatus.FILLED, s.status());
            assertEquals(List.of(99_50L, 99_00L), feed.stream().map(Trade::price).toList());
            assertEquals(Side.SELL, feed.get(0).aggressor());
        }
    }

    // ------------------------------------------------------------------ order types

    @Nested
    class OrderTypes {

        @Test
        void marketOrderSweepsAndNeverRests() {
            sell("a", 50, 100_00);
            sell("b", 50, 101_00);
            Order m = x.market("c", "S", Side.BUY, 200);
            assertEquals(OrderStatus.CANCELLED, m.status());
            assertEquals(100, m.filled());
            assertTrue(m.reason().contains("no more liquidity"));
            assertEquals(List.of(), x.restingOrders("S", Side.BUY));
        }

        @Test
        void marketBuyStopsWhenCashRunsOut() {
            openExtra("poor", 1_000_00);                             // $1,000
            sell("a", 100, 100_00);
            Order m = x.market("poor", "S", Side.BUY, 100);
            assertEquals(10, m.filled());
            assertTrue(m.reason().contains("not enough cash"));
            assertEquals(0, x.account("poor").cash());
        }

        @Test
        void immediateOrCancelKeepsWhatFilled() {
            sell("a", 60, 100_00);
            Order ioc = x.limit("b", "S", Side.BUY, 100, 100_00, TimeInForce.IOC);
            assertEquals(60, ioc.filled());
            assertEquals(OrderStatus.CANCELLED, ioc.status());
            assertEquals(List.of(), x.restingOrders("S", Side.BUY));
        }

        @Test
        void fillOrKillIsAllOrNothing() {
            sell("a", 60, 100_00);
            sell("b", 30, 100_05);
            Order kill = x.limit("c", "S", Side.BUY, 100, 100_05, TimeInForce.FOK);
            assertEquals(0, kill.filled());
            assertEquals(List.of(), feed);
            sell("d", 10, 100_05);
            Order fill = x.limit("c", "S", Side.BUY, 100, 100_05, TimeInForce.FOK);
            assertEquals(OrderStatus.FILLED, fill.status());
        }

        @Test
        void marketOrdersCantBeGtc() {
            Order o = x.submit("a", "S", Side.BUY, OrderType.MARKET, TimeInForce.GTC, 0, 10);
            assertEquals(OrderStatus.REJECTED, o.status());
        }
    }

    // ------------------------------------------------------------------ rules

    @Nested
    class Rules {

        @Test
        void selfTradePreventionCancelsTheIncomingOrder() {
            sell("a", 100, 100_00);
            sell("b", 100, 100_05);
            Order own = buy("a", 150, 100_05);
            assertEquals(OrderStatus.CANCELLED, own.status());
            assertEquals(List.of(), feed, "a's own ask was first in line, so nothing traded");
            Order other = buy("c", 150, 100_05);
            assertEquals(OrderStatus.FILLED, other.status());
        }

        @Test
        void validation() {
            assertEquals(OrderStatus.REJECTED, buy("a", 10, 100_03).status(), "tick size");
            assertEquals(OrderStatus.REJECTED, buy("a", 0, 100_00).status());
            assertEquals(OrderStatus.REJECTED, buy("a", 10, -5).status());
            assertEquals(OrderStatus.REJECTED, buy("a", 10_000_000, 100_00).status(), "cash");
            assertEquals(OrderStatus.REJECTED, sell("a", SHARES + 1, 100_00).status(), "shares");
            assertThrows(ExchangeException.class, () -> x.limit("a", "NOPE", Side.BUY, 1, 100, TimeInForce.GTC));
            assertThrows(ExchangeException.class, () -> x.limit("zz", "S", Side.BUY, 1, 100, TimeInForce.GTC));
        }

        @Test
        void priceBandAroundTheLastTrade() {
            sell("a", 10, 100_00);
            buy("b", 10, 100_00);                                      // last = 100.00, band ±50%
            assertEquals(OrderStatus.REJECTED, buy("c", 10, 49_95).status());
            assertEquals(OrderStatus.OPEN, buy("c", 10, 50_00).status());
            assertEquals(OrderStatus.REJECTED, sell("c", 10, 150_05).status());
        }

        @Test
        void reservationsStopDoubleSpending() {
            openExtra("small", 10_000_00);
            assertEquals(OrderStatus.OPEN, x.limit("small", "S", Side.BUY, 50, 100_00, TimeInForce.GTC).status());
            assertEquals(OrderStatus.OPEN, x.limit("small", "T", Side.BUY, 50, 100_00, TimeInForce.GTC).status());
            assertEquals(OrderStatus.REJECTED, x.limit("small", "S", Side.BUY, 1, 100_00, TimeInForce.GTC).status(),
                    "all $10,000 is promised to two open orders");
        }
    }

    // ------------------------------------------------------------------ cancel & modify

    @Nested
    class CancelModify {

        @Test
        void cancelReleasesAndOnlyTheOwnerMayCancel() {
            Order o = buy("a", 100, 100_00);
            assertThrows(ExchangeException.class, () -> x.cancel("b", o.id()));
            x.cancel("a", o.id());
            assertEquals(OrderStatus.CANCELLED, o.status());
            assertEquals(CASH, x.account("a").availableCash());
            assertThrows(ExchangeException.class, () -> x.cancel("a", o.id()), "already cancelled");
        }

        @Test
        void shrinkingKeepsPriorityEverythingElseLosesIt() {
            Order first = buy("a", 100, 100_00);
            Order second = buy("b", 100, 100_00);
            assertEquals(first, x.modify("a", first.id(), 100_00, 40));
            assertEquals(List.of(first, second), x.restingOrders("S", Side.BUY));
            Order replaced = x.modify("a", first.id(), 100_00, 90);
            assertEquals(List.of(second, replaced), x.restingOrders("S", Side.BUY));
            assertEquals(OrderStatus.CANCELLED, first.status());
            Order repriced = x.modify("b", second.id(), 100_05, 100);
            assertEquals(repriced, x.restingOrders("S", Side.BUY).get(0), "higher bid is now best");
        }

        @Test
        void repricingCanTrade() {
            sell("a", 50, 101_00);
            Order bid = buy("b", 50, 100_00);
            Order crossed = x.modify("b", bid.id(), 101_00, 50);
            assertEquals(OrderStatus.FILLED, crossed.status());
        }
    }

    // ------------------------------------------------------------------ properties & concurrency

    @Test
    void randomOrderFlowKeepsEveryInvariant() {
        Random r = new Random(11);
        List<Order> live = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            String t = TRADERS.get(r.nextInt(4));
            Side side = r.nextBoolean() ? Side.BUY : Side.SELL;
            long price = (9_000 + r.nextInt(200) * 5L);             // 90.00 .. 99.95 on the tick grid
            long qty = 1 + r.nextInt(300);
            Order o = switch (r.nextInt(10)) {
                case 0 -> x.market(t, "S", side, qty);
                case 1 -> x.limit(t, "S", side, qty, price, TimeInForce.IOC);
                case 2 -> x.limit(t, "S", side, qty, price, TimeInForce.FOK);
                case 3 -> {
                    if (!live.isEmpty()) {
                        Order victim = live.remove(r.nextInt(live.size()));
                        if (victim.status().isLive()) {
                            x.cancel(victim.traderId(), victim.id());
                        }
                    }
                    yield null;
                }
                default -> x.limit(t, "S", side, qty, price, TimeInForce.GTC);
            };
            if (o != null && o.status().isLive()) {
                live.add(o);
            }
            if (i % 500 == 0) {
                checkInvariants();
            }
        }
        assertTrue(feed.size() > 100);
        for (Trade t : feed) {
            assertTrue(!t.buyerId().equals(t.sellerId()), "no self trades");
        }
    }

    @Test
    void manyThreadsTradingTwoSymbolsStayConsistent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            int seed = w;
            futures.add(pool.submit(() -> {
                start.await();
                Random r = new Random(seed);
                for (int i = 0; i < 400; i++) {
                    String t = TRADERS.get(r.nextInt(4));
                    String sym = r.nextBoolean() ? "S" : "T";
                    long tick = sym.equals("S") ? 5 : 1;
                    long price = 9_000 + r.nextInt(100) * tick;
                    x.limit(t, sym, r.nextBoolean() ? Side.BUY : Side.SELL, 1 + r.nextInt(50), price,
                            r.nextInt(4) == 0 ? TimeInForce.IOC : TimeInForce.GTC);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long tShares = TRADERS.stream().mapToLong(t -> x.account(t).shares("T")).sum();
        assertEquals(SHARES * TRADERS.size(), tShares);
        // the @AfterEach check verifies cash, S shares, reservations and uncrossed books
    }
}
