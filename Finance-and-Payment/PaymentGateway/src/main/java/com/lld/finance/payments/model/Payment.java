package com.lld.finance.payments.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One payment attempt and its money trail. Changed only by the gateway while holding this object's
 * monitor, so a capture and a refund (or two refunds) can never interleave.
 */
public final class Payment {

    private final String id;
    private final String merchantId;
    private final Money amount;
    private final Instrument instrument;
    private final Instant createdAt;
    private final List<String> history = new ArrayList<>();
    private PaymentStatus status;
    private String processorId;
    private String processorReference;
    private Money captured;
    private Money refunded;
    private String failureReason;

    public Payment(String id, String merchantId, Money amount, Instrument instrument, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.instrument = instrument;
        this.createdAt = createdAt;
        this.captured = Money.of(0, amount.currency());
        this.refunded = Money.of(0, amount.currency());
    }

    public String id() {
        return id;
    }

    public String merchantId() {
        return merchantId;
    }

    /** Amount authorized (requested). */
    public Money amount() {
        return amount;
    }

    public Instrument instrument() {
        return instrument;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized PaymentStatus status() {
        return status;
    }

    public synchronized String processorId() {
        return processorId;
    }

    public synchronized String processorReference() {
        return processorReference;
    }

    public synchronized Money captured() {
        return captured;
    }

    public synchronized Money refunded() {
        return refunded;
    }

    public synchronized Money refundable() {
        return captured.minus(refunded);
    }

    public synchronized String failureReason() {
        return failureReason;
    }

    public synchronized List<String> history() {
        return List.copyOf(history);
    }

    // ---- transitions (gateway only)

    public synchronized void authorized(String processor, String reference, Instant at) {
        processorId = processor;
        processorReference = reference;
        move(PaymentStatus.AUTHORIZED, at, "authorized " + amount + " via " + processor);
    }

    public synchronized void failed(String reason, Instant at) {
        failureReason = reason;
        status = PaymentStatus.FAILED;
        history.add(at + " failed: " + reason);
    }

    public synchronized void captured(Money value, Instant at) {
        captured = value;
        move(PaymentStatus.CAPTURED, at, "captured " + value);
    }

    public synchronized void refunded(Money value, Instant at) {
        refunded = refunded.plus(value);
        move(refunded.equals(captured) ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED, at,
                "refunded " + value + " (total " + refunded + ")");
    }

    public synchronized void voided(Instant at) {
        move(PaymentStatus.VOIDED, at, "voided");
    }

    public synchronized void expired(Instant at) {
        move(PaymentStatus.EXPIRED, at, "authorization expired");
    }

    private void move(PaymentStatus target, Instant at, String note) {
        if (status != null && !status.canMoveTo(target)) {
            throw new PaymentException(PaymentException.Code.INVALID_STATE, id + " can't go from " + status + " to " + target);
        }
        status = target;
        history.add(at + " " + note);
    }

    @Override
    public synchronized String toString() {
        return id + " " + amount + " " + instrument + " [" + status + (failureReason == null ? "" : ": " + failureReason) + "]";
    }
}
