package com.lld.finance.payments.risk;

import com.lld.finance.payments.model.Instrument;
import com.lld.finance.payments.model.Money;

import java.time.Instant;
import java.util.Optional;

/**
 * One fraud check. The gateway runs its rules in order (Chain of Responsibility) before contacting any
 * processor: the first rule that objects blocks the payment, and a blocked payment costs nothing.
 */
@FunctionalInterface
public interface RiskRule {

    record Context(String merchantId, Money amount, Instrument instrument, Instant at) {
    }

    /** @return a reason to block, or empty to let the payment through */
    Optional<String> check(Context context);
}
