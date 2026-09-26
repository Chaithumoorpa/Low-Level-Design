package com.lld.finance.exchange;

import com.lld.finance.exchange.account.Account;
import com.lld.finance.exchange.book.OrderBook;
import com.lld.finance.exchange.core.Exchange;
import com.lld.finance.exchange.model.Order;
import com.lld.finance.exchange.model.Prices;
import com.lld.finance.exchange.model.Side;
import com.lld.finance.exchange.model.TimeInForce;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** One trading session in a fictional stock "ACME" with four traders. */
public class StockExchangeApp {

    public static void main(String[] args) {
        Exchange x = new Exchange(Clock.fixed(Instant.parse("2027-05-03T14:30:00Z"), ZoneOffset.UTC));
        x.listSymbol("ACME", 5, 1000);                            // tick 0.05, price band ±10%
        x.addTradeListener(t -> System.out.println("   [trade] " + t));
        for (String t : List.of("alice", "bob", "carol", "dan")) {
            x.openAccount(t, 100_000_00);                         // $100,000 each
        }
        x.depositShares("alice", "ACME", 1_000);
        x.depositShares("bob", "ACME", 1_000);

        step("Sellers post asks, buyers post bids (nothing crosses yet)");
        x.limit("alice", "ACME", Side.SELL, 100, 101_00, TimeInForce.GTC);
        x.limit("bob", "ACME", Side.SELL, 200, 101_00, TimeInForce.GTC);   // same price, later: behind alice
        x.limit("alice", "ACME", Side.SELL, 300, 102_50, TimeInForce.GTC);
        x.limit("carol", "ACME", Side.BUY, 150, 100_00, TimeInForce.GTC);
        x.limit("dan", "ACME", Side.BUY, 100, 99_50, TimeInForce.GTC);
        printBook(x);

        step("Carol buys 250 at up to 101.00: alice's 100 first (earlier), then 150 of bob's");
        Order carol = x.limit("carol", "ACME", Side.BUY, 250, 101_00, TimeInForce.GTC);
        System.out.println("   " + carol);
        printBook(x);

        step("Dan sends a market buy for 200: sweeps bob's last 50 at 101.00, then 150 at 102.50");
        System.out.println("   " + x.market("dan", "ACME", Side.BUY, 200));

        step("Time in force");
        System.out.println("   " + x.limit("carol", "ACME", Side.BUY, 500, 102_50, TimeInForce.FOK));
        System.out.println("   " + x.limit("carol", "ACME", Side.BUY, 500, 102_50, TimeInForce.IOC));

        step("Self-trade prevention: bob's buy would hit his own ask");
        x.limit("bob", "ACME", Side.SELL, 50, 103_00, TimeInForce.GTC);
        System.out.println("   " + x.limit("bob", "ACME", Side.BUY, 50, 103_00, TimeInForce.GTC));

        step("Validation and risk checks");
        System.out.println("   " + x.limit("dan", "ACME", Side.BUY, 10, 102_53, TimeInForce.GTC));
        System.out.println("   " + x.limit("dan", "ACME", Side.BUY, 10, 80_00, TimeInForce.GTC));
        System.out.println("   " + x.limit("carol", "ACME", Side.SELL, 1_000, 103_00, TimeInForce.GTC));
        System.out.println("   " + x.limit("dan", "ACME", Side.BUY, 10_000, 100_00, TimeInForce.GTC));

        step("Modify: shrinking keeps queue priority; growing or repricing goes to the back");
        Order early = x.limit("carol", "ACME", Side.BUY, 100, 100_50, TimeInForce.GTC);
        Order late = x.limit("dan", "ACME", Side.BUY, 100, 100_50, TimeInForce.GTC);
        x.modify("carol", early.id(), 100_50, 60);
        System.out.println("   queue at 100.50 after carol shrinks: " + ids(x.restingOrders("ACME", Side.BUY), 100_50));
        Order moved = x.modify("carol", early.id(), 100_50, 80);
        System.out.println("   carol grows to 80 -> new order " + moved.id() + ", queue: " + ids(x.restingOrders("ACME", Side.BUY), 100_50));
        x.cancel("dan", late.id());
        printBook(x);

        step("Accounts after the session");
        for (String t : List.of("alice", "bob", "carol", "dan")) {
            Account a = x.account(t);
            System.out.printf("   %-6s cash %12s (available %12s)  ACME %5d (available %5d)%n", t,
                    Prices.format(a.cash()), Prices.format(a.availableCash()), a.shares("ACME"), a.availableShares("ACME"));
        }
        System.out.println("   last price " + Prices.format(x.lastPrice("ACME").orElseThrow()) + ", trades " + x.trades("ACME").size());
    }

    private static List<String> ids(List<Order> orders, long price) {
        return orders.stream().filter(o -> o.price() == price).map(o -> o.id() + "(" + o.traderId() + " " + o.remaining() + ")").toList();
    }

    private static void printBook(Exchange x) {
        List<OrderBook.Level> asks = x.depth("ACME", Side.SELL, 5);
        for (int i = asks.size() - 1; i >= 0; i--) {
            System.out.printf("        ask %8s x %4d (%d)%n", Prices.format(asks.get(i).price()), asks.get(i).quantity(), asks.get(i).orders());
        }
        System.out.println("        --------------------");
        for (OrderBook.Level l : x.depth("ACME", Side.BUY, 5)) {
            System.out.printf("        bid %8s x %4d (%d)%n", Prices.format(l.price()), l.quantity(), l.orders());
        }
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }
}
