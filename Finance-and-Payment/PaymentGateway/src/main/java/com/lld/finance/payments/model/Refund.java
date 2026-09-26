package com.lld.finance.payments.model;

import java.time.Instant;

public record Refund(String id, String paymentId, Money amount, String processorReference, Instant at) {
}
