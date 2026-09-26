package com.lld.finance.payments.core;

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
import com.lld.finance.payments.processor.ProcessorClient;
import com.lld.finance.payments.processor.ProcessorClient.AuthResponse;
import com.lld.finance.payments.processor.ProcessorUnavailable;
import com.lld.finance.payments.risk.RiskRule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Facade merchants call. Each operation is:
 * <ol>
 *   <li><b>idempotent</b> by merchant-supplied key (retries return the first result; a different request
 *       reusing a key is a conflict; concurrent duplicates wait for the first),</li>
 *   <li>checked against the payment's <b>state machine</b> under the payment's lock,</li>
 *   <li>routed to the cheapest healthy <b>processor</b>, failing over on outages (never on declines),</li>
 *   <li>recorded in a <b>double-entry ledger</b>, and announced via <b>webhooks</b>.</li>
 * </ol>
 */
public final class PaymentGateway {

    public record PaymentRequest(String merchantId, String idempotencyKey, Money amount, Instrument instrument,
                                 boolean autoCapture) {
    }

    private record IdempotencyRecord(String fingerprint, CompletableFuture<Object> result) {
    }

    private final Map<String, Merchant> merchants = new ConcurrentHashMap<>();
    private final List<ProcessorClient> processors = new CopyOnWriteArrayList<>();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, List<Refund>> refunds = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyRecord> idempotency = new ConcurrentHashMap<>();
    private final List<RiskRule> riskRules = new CopyOnWriteArrayList<>();
    private final List<WebhookListener> webhooks = new CopyOnWriteArrayList<>();
    private final AtomicLong paymentSeq = new AtomicLong();
    private final AtomicLong refundSeq = new AtomicLong();
    private final Ledger ledger = new Ledger();
    private final CardVault vault;
    private final Duration authValidity;
    private final Clock clock;

    public PaymentGateway(Duration authValidity, Clock clock) {
        this.authValidity = authValidity;
        this.clock = Objects.requireNonNull(clock);
        this.vault = new CardVault(clock);
    }

    // ------------------------------------------------------------------ setup

    public void registerMerchant(Merchant m) {
        merchants.put(m.id(), m);
    }

    public void registerProcessor(ProcessorClient p) {
        processors.add(p);
        breakers.put(p.id(), new CircuitBreaker(3, Duration.ofSeconds(30), clock));
    }

    public void addRiskRule(RiskRule rule) {
        riskRules.add(rule);
    }

    public void addWebhookListener(WebhookListener l) {
        webhooks.add(l);
    }

    /** Card details go straight to the vault; the merchant gets a token back. */
    public Instrument.Card tokenizeCard(String pan, int expMonth, int expYear) {
        return vault.tokenize(pan, expMonth, expYear);
    }

    // ------------------------------------------------------------------ pay (authorize [+ capture])

    public Payment pay(PaymentRequest req) {
        Merchant merchant = merchant(req.merchantId());
        String fingerprint = req.amount() + "|" + req.instrument().fingerprint() + "|" + req.autoCapture();
        return idempotent(merchant.id(), "pay", req.idempotencyKey(), fingerprint, () -> doPay(merchant, req));
    }

    private Payment doPay(Merchant merchant, PaymentRequest req) {
        if (!req.amount().isPositive()) {
            throw new PaymentException(Code.INVALID_REQUEST, "Amount must be positive");
        }
        if (req.instrument() instanceof Instrument.Card c
                && YearMonth.of(c.expYear(), c.expMonth()).isBefore(YearMonth.now(clock.withZone(ZoneOffset.UTC)))) {
            throw new PaymentException(Code.INVALID_REQUEST, "Card expired");
        }
        Payment p = new Payment("pay_" + paymentSeq.incrementAndGet(), merchant.id(), req.amount(), req.instrument(), now());
        payments.put(p.id(), p);
        synchronized (p) {
            RiskRule.Context ctx = new RiskRule.Context(merchant.id(), req.amount(), req.instrument(), now());
            for (RiskRule rule : riskRules) {
                Optional<String> reason = rule.check(ctx);
                if (reason.isPresent()) {
                    p.failed("blocked by risk: " + reason.get(), now());
                    emit(p, "payment.failed");
                    return p;
                }
            }
            authorize(p);
            if (p.status() == PaymentStatus.AUTHORIZED && req.autoCapture()) {
                captureLocked(merchant, p, p.amount());
            }
        }
        return p;
    }

    /** Cheapest processor first; on timeout try the next; a decline is final. */
    private void authorize(Payment p) {
        List<ProcessorClient> route = processors.stream()
                .filter(x -> x.supports(p.instrument()))
                .sorted(Comparator.comparingInt(ProcessorClient::costBasisPoints).thenComparing(ProcessorClient::id))
                .toList();
        if (route.isEmpty()) {
            p.failed("no processor supports " + p.instrument(), now());
            emit(p, "payment.failed");
            return;
        }
        ProcessorClient.CardData card = p.instrument() instanceof Instrument.Card c ? vault.lookup(c.token()) : null;
        List<String> skipped = new ArrayList<>();
        for (ProcessorClient proc : route) {
            CircuitBreaker breaker = breakers.get(proc.id());
            if (!breaker.allowRequest()) {
                skipped.add(proc.id() + " (circuit open)");
                continue;
            }
            try {
                AuthResponse r = proc.authorize(p.id(), p.amount(), p.instrument(), card);
                breaker.recordSuccess();
                if (r.approved()) {
                    p.authorized(proc.id(), r.reference(), now());
                    emit(p, "payment.authorized");
                } else {
                    p.failed("declined: " + r.declineReason(), now());
                    emit(p, "payment.failed");
                }
                return;
            } catch (ProcessorUnavailable e) {
                breaker.recordFailure();
                skipped.add(proc.id() + " (" + e.getMessage() + ")");
            }
        }
        p.failed("no processor available: " + String.join(", ", skipped), now());
        emit(p, "payment.failed");
    }

    // ------------------------------------------------------------------ capture / void

    /** Captures up to the authorized amount (partial capture releases the rest). */
    public Payment capture(String merchantId, String paymentId, Money amount) {
        Merchant merchant = merchant(merchantId);
        Payment p = payment(merchantId, paymentId);
        synchronized (p) {
            captureLocked(merchant, p, amount);
        }
        return p;
    }

    private void captureLocked(Merchant merchant, Payment p, Money amount) {
        requireState(p, PaymentStatus.CAPTURED);
        if (!amount.isPositive() || amount.compareTo(p.amount()) > 0) {
            throw new PaymentException(Code.INVALID_REQUEST, "Capture must be between 0 and " + p.amount());
        }
        try {
            processor(p).capture(p.processorReference(), amount);
        } catch (ProcessorUnavailable e) {
            throw new PaymentException(Code.PROCESSOR_UNAVAILABLE, "Capture failed, retry: " + e.getMessage());
        }
        p.captured(amount, now());
        long fee = Math.min(amount.minor(), merchant.feeFor(amount.minor()));
        Map<String, Long> legs = new LinkedHashMap<>();
        legs.put("processor:" + p.processorId(), amount.minor());
        legs.put("merchant:" + merchant.id(), -(amount.minor() - fee));
        legs.put("fees", -fee);
        ledger.post(p.id() + " capture", amount.currency(), legs);
        emit(p, "payment.captured");
    }

    public Payment voidPayment(String merchantId, String paymentId) {
        Payment p = payment(merchantId, paymentId);
        synchronized (p) {
            requireState(p, PaymentStatus.VOIDED);
            try {
                processor(p).voidAuthorization(p.processorReference());
            } catch (ProcessorUnavailable e) {
                throw new PaymentException(Code.PROCESSOR_UNAVAILABLE, "Void failed, retry: " + e.getMessage());
            }
            p.voided(now());
            emit(p, "payment.voided");
        }
        return p;
    }

    /** Scheduler job: authorizations not captured in time are released back to the customer. */
    public List<Payment> expireAuthorizations() {
        List<Payment> expired = new ArrayList<>();
        for (Payment p : payments.values()) {
            synchronized (p) {
                if (p.status() == PaymentStatus.AUTHORIZED && !now().isBefore(p.createdAt().plus(authValidity))) {
                    try {
                        processor(p).voidAuthorization(p.processorReference());
                    } catch (ProcessorUnavailable ignored) {
                        // the bank drops stale holds on its own; we still stop treating it as capturable
                    }
                    p.expired(now());
                    emit(p, "payment.expired");
                    expired.add(p);
                }
            }
        }
        expired.sort(Comparator.comparing(Payment::id));
        return expired;
    }

    // ------------------------------------------------------------------ refunds

    public Refund refund(String merchantId, String paymentId, String idempotencyKey, Money amount) {
        Payment p = payment(merchantId, paymentId);
        return idempotent(merchantId, "refund", idempotencyKey, paymentId + "|" + amount, () -> {
            synchronized (p) {
                if (p.status() != PaymentStatus.CAPTURED && p.status() != PaymentStatus.PARTIALLY_REFUNDED) {
                    throw new PaymentException(Code.INVALID_STATE, p.id() + " is " + p.status() + "; only captured payments can be refunded");
                }
                if (!amount.isPositive() || amount.compareTo(p.refundable()) > 0) {
                    throw new PaymentException(Code.INVALID_REQUEST, "Refund must be between 0 and " + p.refundable());
                }
                String ref;
                try {
                    ref = processor(p).refund(p.processorReference(), amount);
                } catch (ProcessorUnavailable e) {
                    throw new PaymentException(Code.PROCESSOR_UNAVAILABLE, "Refund failed, retry: " + e.getMessage());
                }
                p.refunded(amount, now());
                Map<String, Long> legs = new LinkedHashMap<>();
                legs.put("merchant:" + merchantId, amount.minor());                 // merchant bears the refund
                legs.put("processor:" + p.processorId(), -amount.minor());
                ledger.post(p.id() + " refund", amount.currency(), legs);
                Refund r = new Refund("re_" + refundSeq.incrementAndGet(), p.id(), amount, ref, now());
                refunds.computeIfAbsent(p.id(), k -> new CopyOnWriteArrayList<>()).add(r);
                emit(p, "payment.refunded");
                return r;
            }
        });
    }

    // ------------------------------------------------------------------ payouts

    /** What we owe the merchant in a currency (captured − fees − refunds − earlier payouts). */
    public Money merchantBalance(String merchantId, String currency) {
        merchant(merchantId);
        return Money.of(-ledger.balance("merchant:" + merchantId, currency), currency);
    }

    /** Sends the merchant's balance to their bank account. @return amount paid out (0 if nothing owed) */
    public synchronized Money payout(String merchantId, String currency) {
        Money owed = merchantBalance(merchantId, currency);
        if (owed.isPositive()) {
            Map<String, Long> legs = new LinkedHashMap<>();
            legs.put("merchant:" + merchantId, owed.minor());
            legs.put("bank", -owed.minor());
            ledger.post("payout " + merchantId, currency, legs);
            return owed;
        }
        return Money.of(0, currency);
    }

    // ------------------------------------------------------------------ queries

    /** Merchants only see their own payments. */
    public Payment payment(String merchantId, String paymentId) {
        Payment p = payments.get(paymentId);
        if (p == null || !p.merchantId().equals(merchantId)) {
            throw new PaymentException(Code.NOT_FOUND, "No payment " + paymentId);
        }
        return p;
    }

    public List<Refund> refunds(String paymentId) {
        return List.copyOf(refunds.getOrDefault(paymentId, List.of()));
    }

    public Ledger ledger() {
        return ledger;
    }

    public CircuitBreaker.State circuit(String processorId) {
        return breakers.get(processorId).state();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Runs {@code action} once per (merchant, operation, key). Concurrent duplicates wait for the first
     * call and get its result. A request that failed validation frees the key so it can be corrected.
     */
    @SuppressWarnings("unchecked")
    private <T> T idempotent(String merchantId, String op, String key, String fingerprint, Supplier<T> action) {
        if (key == null) {
            return action.get();
        }
        String slot = merchantId + "|" + op + "|" + key;
        IdempotencyRecord mine = new IdempotencyRecord(fingerprint, new CompletableFuture<>());
        IdempotencyRecord existing = idempotency.putIfAbsent(slot, mine);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new PaymentException(Code.IDEMPOTENCY_CONFLICT, "Key " + key + " was used for a different request");
            }
            try {
                return (T) existing.result().join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
        }
        try {
            T result = action.get();
            mine.result().complete(result);
            return result;
        } catch (RuntimeException e) {
            idempotency.remove(slot, mine);
            mine.result().completeExceptionally(e);
            throw e;
        }
    }

    private void requireState(Payment p, PaymentStatus target) {
        if (p.status() == null || !p.status().canMoveTo(target)) {
            throw new PaymentException(Code.INVALID_STATE, p.id() + " is " + p.status() + "; can't move to " + target);
        }
    }

    private ProcessorClient processor(Payment p) {
        return processors.stream().filter(x -> x.id().equals(p.processorId())).findFirst().orElseThrow();
    }

    private Merchant merchant(String id) {
        Merchant m = merchants.get(id);
        if (m == null) {
            throw new PaymentException(Code.NOT_FOUND, "No merchant " + id);
        }
        return m;
    }

    private void emit(Payment p, String type) {
        webhooks.forEach(w -> w.onEvent(p.merchantId(), type, p));
    }

    private Instant now() {
        return clock.instant();
    }
}
