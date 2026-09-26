package com.lld.finance.exchange.model;

/** API misuse: unknown symbol, trader or order; not your order; order no longer live. */
public class ExchangeException extends RuntimeException {

    public ExchangeException(String message) {
        super(message);
    }
}
