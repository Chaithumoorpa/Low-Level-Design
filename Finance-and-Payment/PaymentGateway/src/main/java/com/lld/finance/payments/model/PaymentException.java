package com.lld.finance.payments.model;

/**
 * A request the gateway rejects (API error). Note that a <em>declined</em> card is not an exception:
 * it is a normal outcome, returned as a FAILED payment with a reason.
 */
public class PaymentException extends RuntimeException {

    public enum Code {
        INVALID_REQUEST, NOT_FOUND, INVALID_STATE, IDEMPOTENCY_CONFLICT, PROCESSOR_UNAVAILABLE
    }

    private final Code code;

    public PaymentException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
