package com.lld.finance.payments;

import com.lld.finance.payments.core.ManualClock;
import com.lld.finance.payments.core.PaymentGateway;
import com.lld.finance.payments.core.PaymentGateway.PaymentRequest;
import com.lld.finance.payments.model.Instrument;
import com.lld.finance.payments.model.Merchant;
import com.lld.finance.payments.model.Money;
import com.lld.finance.payments.model.Payment;
import com.lld.finance.payments.model.PaymentException;
import com.lld.finance.payments.processor.FakeProcessor;
import com.lld.finance.payments.risk.RiskRules;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;

/** A day at a small payment gateway, with test card numbers and simulated processors. */
public class PaymentGatewayApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2027-04-01T10:00:00Z"));
        PaymentGateway gateway = new PaymentGateway(Duration.ofDays(7), clock);
        FakeProcessor cheap = new FakeProcessor("acme-pay", 180, Set.of("VISA", "MASTERCARD"), false);
        FakeProcessor backup = new FakeProcessor("globex", 250, Set.of("VISA", "MASTERCARD", "AMEX"), true);
        gateway.registerProcessor(cheap);
        gateway.registerProcessor(backup);
        gateway.registerMerchant(new Merchant("shop", "Book Shop", 290, 30));          // 2.9% + 30c
        gateway.addRiskRule(RiskRules.maxAmount(500_000));
        gateway.addRiskRule(RiskRules.velocity(3, Duration.ofMinutes(10)));
        gateway.addWebhookListener((merchant, type, p) -> System.out.println("   [webhook " + merchant + "] " + type + " " + p.id()));

        step("Tokenize: the card number goes to the vault, the shop keeps a token");
        Instrument.Card visa = gateway.tokenizeCard("4242 4242 4242 4242", 12, 2030);
        System.out.println("   " + visa);
        attempt(() -> gateway.tokenizeCard("4242 4242 4242 4241", 12, 2030));
        attempt(() -> gateway.tokenizeCard("5555 5555 5555 4444", 1, 2027));

        step("Checkout: authorize now, capture when the order ships");
        Payment order = gateway.pay(new PaymentRequest("shop", "order-1001", Money.of(59_90, "USD"), visa, false));
        System.out.println("   " + order + " via " + order.processorId());
        System.out.println("   customer double-clicks 'Pay': " + gateway.pay(new PaymentRequest("shop", "order-1001",
                Money.of(59_90, "USD"), visa, false)).id() + " (same payment, no second charge)");
        attempt(() -> gateway.pay(new PaymentRequest("shop", "order-1001", Money.of(99_00, "USD"), visa, false)));
        gateway.capture("shop", order.id(), Money.of(49_90, "USD"));
        System.out.println("   one book was out of stock: captured " + order.captured() + " of " + order.amount());

        step("acme-pay times out: fail over to globex");
        cheap.failNext(3);
        Instrument.Card mc = gateway.tokenizeCard("5555 5555 5555 4444", 6, 2029);
        for (int i = 1; i <= 3; i++) {
            Payment p = gateway.pay(new PaymentRequest("shop", "order-20" + i, Money.of(20_00, "USD"), mc, true));
            System.out.println("   " + p + " via " + p.processorId());
        }
        System.out.println("   acme-pay circuit: " + gateway.circuit("acme-pay"));
        Payment skipped = gateway.pay(new PaymentRequest("shop", "order-301", Money.of(15_00, "USD"), visa, true));
        System.out.println("   next payment skips acme-pay without waiting: " + skipped + " via " + skipped.processorId());
        clock.advance(Duration.ofSeconds(30));
        Payment probe = gateway.pay(new PaymentRequest("shop", "order-302", Money.of(15_00, "USD"), visa, true));
        System.out.println("   30s later a probe succeeds: " + probe + " via " + probe.processorId()
                + ", circuit " + gateway.circuit("acme-pay"));

        step("Declines and risk blocks are outcomes, not errors");
        Instrument.Card poor = gateway.tokenizeCard("4000 0000 0000 0002", 3, 2031);
        System.out.println("   " + gateway.pay(new PaymentRequest("shop", "order-401", Money.of(80_00, "USD"), poor, true)));
        System.out.println("   " + gateway.pay(new PaymentRequest("shop", "order-402", Money.of(9_999_00, "USD"), visa, true)));
        System.out.println("   " + gateway.pay(new PaymentRequest("shop", "order-403", Money.of(5_00, "USD"), mc, true)));
        System.out.println("   (MasterCard was already used 3 times in 10 minutes: card-testing defence)");

        step("Refunds");
        gateway.refund("shop", order.id(), "rf-1", Money.of(20_00, "USD"));
        gateway.refund("shop", order.id(), "rf-1", Money.of(20_00, "USD"));
        attempt(() -> gateway.refund("shop", order.id(), "rf-2", Money.of(40_00, "USD")));
        System.out.println("   " + order + ", refunded " + order.refunded());

        step("An authorization nobody captured expires after 7 days");
        clock.advance(Duration.ofMinutes(10));                 // clear the velocity window for the Visa card
        Payment forgotten = gateway.pay(new PaymentRequest("shop", "order-501", Money.of(30_00, "USD"), visa, false));
        clock.advance(Duration.ofDays(7));
        gateway.expireAuthorizations();
        attempt(() -> gateway.capture("shop", forgotten.id(), Money.of(30_00, "USD")));

        step("Ledger and payout");
        System.out.println("   owed to the shop: " + gateway.merchantBalance("shop", "USD"));
        System.out.println("   paid out: " + gateway.payout("shop", "USD") + "; owed now: " + gateway.merchantBalance("shop", "USD"));
        System.out.println("   gateway fee revenue: " + Money.of(-gateway.ledger().balance("fees", "USD"), "USD"));
        System.out.println("   sum of all ledger entries: " + gateway.ledger().total("USD") + " (double entry: always 0)");
        System.out.println("   history of " + order.id() + ":");
        order.history().forEach(h -> System.out.println("     " + h));
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (PaymentException e) {
            System.out.println("   [" + e.code() + "] " + e.getMessage());
        }
    }
}
