package com.lld.finance.payments.processor;

/** The processor could not answer (timeout, outage). The outcome is unknown, not a decline. */
public class ProcessorUnavailable extends Exception {

    public ProcessorUnavailable(String message) {
        super(message);
    }
}
