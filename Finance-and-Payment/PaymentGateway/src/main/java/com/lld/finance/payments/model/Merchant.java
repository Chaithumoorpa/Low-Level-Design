package com.lld.finance.payments.model;

/** A business using the gateway. Pricing: a percentage (basis points) plus a fixed fee per captured payment. */
public record Merchant(String id, String name, int feeBasisPoints, long fixedFeeMinor) {

    public long feeFor(long capturedMinor) {
        return Math.floorDiv(capturedMinor * feeBasisPoints + 5_000, 10_000) + fixedFeeMinor;
    }
}
