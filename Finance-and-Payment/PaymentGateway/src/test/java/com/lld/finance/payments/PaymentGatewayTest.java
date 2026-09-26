package com.lld.finance.payments;

import com.lld.finance.payments.core.CardVault;
import com.lld.finance.payments.core.ManualClock;
import com.lld.finance.payments.core.PaymentGateway;
import com.lld.finance.payments.core.PaymentGateway.PaymentRequest;
import com.lld.finance.payments.ledger.Ledger;
import com.lld.finance.payments.model.Instrument;
import com.lld.finance.payments.model.Merchant;
import com.lld.finance.payments.model.Money;
import com.lld.finance.payments.model.Payment;
import com.lld.finance.payments.model.PaymentException;
import com.lld.finance.payments.model.PaymentException.Code;
import com.lld.finance.payments.model.PaymentStatus;
import com.lld.finance.payments.model.Refund;
import com.lld.finance.payments.processor.CircuitBreaker;
import com.lld.finance.payments.processor.FakeProcessor;
import com.lld.finance.payments.risk.RiskRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentGatewayTest {

    private ManualClock clock;
    private PaymentGateway gw;
    private FakeProcessor cheap;
    private FakeProcessor backup;
    private Instrument.Card visa;
    private final List<String> webhooks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2027-04-01T10:00:00Z"));
        gw = new PaymentGateway(Duration.ofDays(7), clock);
        cheap = new FakeProcessor("cheap", 100, Set.of("VISA", "MASTERCARD"), false);
        backup = new FakeProcessor("backup", 200, Set.of("VISA", "MASTERCARD", "AMEX"), true);
        gw.registerProcessor(backup);
        gw.registerProcessor(cheap);
        gw.registerMerchant(new Merchant("m1", "Shop", 300, 30));
        gw.registerMerchant(new Merchant("m2", "Other", 300, 30));
        gw.addWebhookListener((m, type, p) -> webhooks.add(type));
        visa = gw.tokenizeCard("4242424242424242", 12, 2030);
    }

    @AfterEach
    void ledgerAlwaysBalances() {
        assertEquals(0, gw.ledger().total("USD"));
    }

    private static Money usd(long minor) {
        return Money.of(minor, "USD");
    }

    private Payment pay(String key, long minor, boolean capture) {
        return gw.pay(new PaymentRequest("m1", key, usd(minor), visa, capture));
    }

    // ------------------------------------------------------------------ vault

    @Nested
    class Vault {

        @ParameterizedTest(name = "{0} valid={1}")
        @CsvSource({"4242424242424242,true", "4242424242424241,false", "5555555555554444,true",
                "378282246310005,true", "79927398713,true", "79927398710,false"})
        void luhn(String digits, boolean valid) {
            assertEquals(valid, CardVault.luhnValid(digits));
        }

        @Test
        void tokensHideTheNumberAndAreStablePerCard() {
            assertEquals("VISA", visa.brand());
            assertEquals("4242", visa.last4());
            assertTrue(visa.token().startsWith("tok_"));
            assertFalse(visa.toString().contains("424242424242"));
            assertEquals(visa.token(), gw.tokenizeCard("4242-4242-4242-4242", 12, 2030).token());
            assertEquals("AMEX", gw.tokenizeCard("378282246310005", 1, 2031).brand());
            assertEquals("MASTERCARD", gw.tokenizeCard("5555555555554444", 1, 2031).brand());
        }

        @Test
        void rejectsBadCards() {
            assertThrows(PaymentException.class, () -> gw.tokenizeCard("4242424242424241", 12, 2030));
            assertThrows(PaymentException.class, () -> gw.tokenizeCard("1234", 12, 2030));
            assertThrows(PaymentException.class, () -> gw.tokenizeCard("4242424242424242", 13, 2030));
            assertThrows(PaymentException.class, () -> gw.tokenizeCard("4242424242424242", 3, 2027), "expired");
            gw.tokenizeCard("4242424242424242", 4, 2027);                                  // this month is fine
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Nested
    class Lifecycle {

        @Test
        void authorizeThenPartialCaptureThenRefunds() {
            Payment p = pay("k1", 10_000, false);
            assertEquals(PaymentStatus.AUTHORIZED, p.status());
            assertEquals("cheap", p.processorId(), "cheapest route");
            gw.capture("m1", p.id(), usd(8_000));
            assertEquals(usd(8_000), p.captured());
            Refund r1 = gw.refund("m1", p.id(), "r1", usd(3_000));
            assertEquals(PaymentStatus.PARTIALLY_REFUNDED, p.status());
            assertThrows(PaymentException.class, () -> gw.refund("m1", p.id(), "r2", usd(5_001)));
            gw.refund("m1", p.id(), "r3", usd(5_000));
            assertEquals(PaymentStatus.REFUNDED, p.status());
            assertEquals(2, gw.refunds(p.id()).size());
            assertEquals(r1.id(), gw.refunds(p.id()).get(0).id());
            assertEquals(List.of("payment.authorized", "payment.captured", "payment.refunded", "payment.refunded"), webhooks);
        }

        @Test
        void illegalTransitionsAreRejected() {
            Payment p = pay("k1", 1_000, false);
            assertThrows(PaymentException.class, () -> gw.refund("m1", p.id(), "r", usd(100)), "not captured");
            assertThrows(PaymentException.class, () -> gw.capture("m1", p.id(), usd(1_001)), "above authorization");
            assertThrows(PaymentException.class, () -> gw.capture("m1", p.id(), usd(0)));
            gw.voidPayment("m1", p.id());
            assertEquals(PaymentStatus.VOIDED, p.status());
            PaymentException e = assertThrows(PaymentException.class, () -> gw.capture("m1", p.id(), usd(1_000)));
            assertEquals(Code.INVALID_STATE, e.code());
            Payment captured = pay("k2", 1_000, true);
            assertThrows(PaymentException.class, () -> gw.voidPayment("m1", captured.id()));
            assertThrows(PaymentException.class, () -> gw.capture("m1", captured.id(), usd(1_000)), "only once");
        }

        @Test
        void merchantsOnlySeeTheirOwnPayments() {
            Payment p = pay("k1", 1_000, true);
            PaymentException e = assertThrows(PaymentException.class, () -> gw.payment("m2", p.id()));
            assertEquals(Code.NOT_FOUND, e.code());
            assertThrows(PaymentException.class, () -> gw.refund("m2", p.id(), "x", usd(100)));
        }

        @Test
        void currencyMismatchAndBadAmounts() {
            Payment p = pay("k1", 1_000, true);
            assertThrows(IllegalArgumentException.class, () -> gw.refund("m1", p.id(), "r", Money.of(100, "EUR")));
            assertThrows(PaymentException.class, () -> pay("k2", 0, true));
        }

        @Test
        void uncapturedAuthorizationsExpire() {
            Payment p = pay("k1", 1_000, false);
            clock.advance(Duration.ofDays(7).minusSeconds(1));
            assertEquals(List.of(), gw.expireAuthorizations());
            clock.advance(Duration.ofSeconds(1));
            assertEquals(List.of(p), gw.expireAuthorizations());
            assertEquals(PaymentStatus.EXPIRED, p.status());
            assertTrue(cheap.calls().stream().anyMatch(c -> c.startsWith("void")));
        }

        @Test
        void declinesAreFailedPaymentsNotErrorsAndDontFailOver() {
            Instrument.Card poor = gw.tokenizeCard("4000000000000002", 1, 2031);
            Payment p = gw.pay(new PaymentRequest("m1", "k", usd(1_000), poor, true));
            assertEquals(PaymentStatus.FAILED, p.status());
            assertEquals("declined: insufficient funds", p.failureReason());
            assertEquals(List.of(), backup.calls(), "a decline is the bank's answer: no retry elsewhere");
        }

        @Test
        void unsupportedInstrumentFails() {
            FakeProcessor none = new FakeProcessor("none", 1, Set.of(), false);
            PaymentGateway g2 = new PaymentGateway(Duration.ofDays(7), clock);
            g2.registerProcessor(none);
            g2.registerMerchant(new Merchant("m", "M", 0, 0));
            Payment p = g2.pay(new PaymentRequest("m", null, usd(100), new Instrument.Upi("a@bank"), true));
            assertTrue(p.failureReason().startsWith("no processor supports"));
        }
    }

    // ------------------------------------------------------------------ idempotency

    @Nested
    class Idempotency {

        @Test
        void sameKeySameRequestReturnsTheFirstPayment() {
            Payment first = pay("order-1", 1_000, true);
            assertSame(first, pay("order-1", 1_000, true));
            assertEquals(1, cheap.calls().stream().filter(c -> c.startsWith("authorize")).count());
        }

        @Test
        void sameKeyDifferentRequestIsAConflict() {
            pay("order-1", 1_000, true);
            PaymentException e = assertThrows(PaymentException.class, () -> pay("order-1", 2_000, true));
            assertEquals(Code.IDEMPOTENCY_CONFLICT, e.code());
        }

        @Test
        void keysAreScopedPerMerchantAndOperation() {
            Payment a = pay("k", 1_000, true);
            Payment b = gw.pay(new PaymentRequest("m2", "k", usd(1_000), visa, true));
            assertTrue(a != b);
            gw.refund("m1", a.id(), "k", usd(100));                                         // same key, other operation
            assertEquals(1, gw.refunds(a.id()).size());
            gw.refund("m1", a.id(), "k", usd(100));
            assertEquals(1, gw.refunds(a.id()).size(), "refund retry is not a second refund");
        }

        @Test
        void aRejectedRequestFreesItsKey() {
            assertThrows(PaymentException.class, () -> pay("k", 0, true));
            assertEquals(PaymentStatus.CAPTURED, pay("k", 500, true).status());
        }

        @Test
        void concurrentDuplicatesChargeOnce() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            Set<Payment> seen = ConcurrentHashMap.newKeySet();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    seen.add(pay("double-click", 2_500, true));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(1, seen.size());
            assertEquals(1, cheap.calls().stream().filter(c -> c.startsWith("authorize")).count());
        }
    }

    // ------------------------------------------------------------------ routing & resilience

    @Nested
    class Routing {

        @Test
        void outagesFailOverAndOpenTheCircuit() {
            cheap.failNext(3);
            for (int i = 0; i < 3; i++) {
                assertEquals("backup", pay("k" + i, 1_000, true).processorId());
            }
            assertEquals(CircuitBreaker.State.OPEN, gw.circuit("cheap"));
            int callsBefore = cheap.calls().size();
            assertEquals("backup", pay("k3", 1_000, true).processorId());
            assertEquals(callsBefore, cheap.calls().size(), "open circuit: not even tried");
            clock.advance(Duration.ofSeconds(30));
            assertEquals("cheap", pay("k4", 1_000, true).processorId(), "half-open probe succeeds");
            assertEquals(CircuitBreaker.State.CLOSED, gw.circuit("cheap"));
        }

        @Test
        void failedProbeReopensTheCircuit() {
            CircuitBreaker b = new CircuitBreaker(2, Duration.ofSeconds(10), clock);
            b.recordFailure();
            b.recordFailure();
            assertFalse(b.allowRequest());
            clock.advance(Duration.ofSeconds(10));
            assertTrue(b.allowRequest());
            assertEquals(CircuitBreaker.State.HALF_OPEN, b.state());
            assertFalse(b.allowRequest(), "only one probe");
            b.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, b.state());
        }

        @Test
        void everyProcessorDownFailsThePayment() {
            cheap.failNext(1);
            backup.failNext(1);
            Payment p = pay("k", 1_000, true);
            assertEquals(PaymentStatus.FAILED, p.status());
            assertTrue(p.failureReason().startsWith("no processor available"));
        }

        @Test
        void captureTimeoutIsRetryable() {
            Payment p = pay("k", 1_000, false);
            cheap.failNext(1);
            PaymentException e = assertThrows(PaymentException.class, () -> gw.capture("m1", p.id(), usd(1_000)));
            assertEquals(Code.PROCESSOR_UNAVAILABLE, e.code());
            assertEquals(PaymentStatus.AUTHORIZED, p.status());
            gw.capture("m1", p.id(), usd(1_000));
            assertEquals(PaymentStatus.CAPTURED, p.status());
        }

        @Test
        void amexOnlyGoesWhereSupported() {
            Instrument.Card amex = gw.tokenizeCard("378282246310005", 1, 2031);
            assertEquals("backup", gw.pay(new PaymentRequest("m1", "a", usd(1_000), amex, true)).processorId());
        }
    }

    // ------------------------------------------------------------------ risk

    @Test
    void riskRulesBlockBeforeAnyProcessorCall() {
        gw.addRiskRule(RiskRules.maxAmount(50_000));
        gw.addRiskRule(RiskRules.blocked(Set.of("upi:thief@bank")));
        gw.addRiskRule(RiskRules.velocity(2, Duration.ofMinutes(10)));
        assertTrue(pay("a", 60_000, true).failureReason().contains("above limit"));
        assertEquals(PaymentStatus.CAPTURED, pay("b", 100, true).status());
        assertEquals(PaymentStatus.CAPTURED, pay("c", 100, true).status());
        assertTrue(pay("d", 100, true).failureReason().contains("too many attempts"));
        clock.advance(Duration.ofMinutes(10));
        assertEquals(PaymentStatus.CAPTURED, pay("e", 100, true).status(), "window slid");
        Payment thief = gw.pay(new PaymentRequest("m1", "f", usd(100), new Instrument.Upi("thief@bank"), true));
        assertTrue(thief.failureReason().contains("blocked"));
        assertEquals(2 + 1, cheap.calls().stream().filter(c -> c.startsWith("authorize")).count());
    }

    // ------------------------------------------------------------------ ledger

    @Test
    void ledgerTracksFeesRefundsAndPayouts() {
        Payment p = pay("k", 10_000, true);                       // fee 300 + 30
        Ledger l = gw.ledger();
        assertEquals(10_000, l.balance("processor:cheap", "USD"));
        assertEquals(usd(9_670), gw.merchantBalance("m1", "USD"));
        assertEquals(-330, l.balance("fees", "USD"));
        gw.refund("m1", p.id(), "r", usd(2_000));
        assertEquals(usd(7_670), gw.merchantBalance("m1", "USD"), "fees are not returned on refund");
        assertEquals(usd(7_670), gw.payout("m1", "USD"));
        assertEquals(usd(0), gw.merchantBalance("m1", "USD"));
        assertEquals(usd(0), gw.payout("m1", "USD"));
        assertEquals(-7_670, l.balance("bank", "USD"));
        assertThrows(IllegalStateException.class, () -> l.post("bad", "USD", Map.of("a", 1L, "b", 2L)));
    }

    @Test
    void concurrentRefundsNeverExceedTheCapture() throws Exception {
        Payment p = pay("k", 10_000, true);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String key = "r" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    gw.refund("m1", p.id(), key, usd(700));
                    ok.incrementAndGet();
                } catch (PaymentException tooMuch) {
                    // expected once the balance runs out
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(14, ok.get(), "14 x 7.00 = 98.00 fits in 100.00; the 15th doesn't");
        assertEquals(usd(9_800), p.refunded());
    }
}
