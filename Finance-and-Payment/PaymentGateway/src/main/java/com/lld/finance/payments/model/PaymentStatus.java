package com.lld.finance.payments.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Card payments are two-step: <b>authorize</b> (the bank holds the money) then <b>capture</b> (the money
 * moves, e.g. when the order ships). The table below is the only place transitions are defined.
 */
public enum PaymentStatus {
    AUTHORIZED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, VOIDED, EXPIRED, FAILED;

    public Set<PaymentStatus> next() {
        return switch (this) {
            case AUTHORIZED -> EnumSet.of(CAPTURED, VOIDED, EXPIRED);
            case CAPTURED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case PARTIALLY_REFUNDED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case REFUNDED, VOIDED, EXPIRED, FAILED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canMoveTo(PaymentStatus target) {
        return next().contains(target);
    }
}
