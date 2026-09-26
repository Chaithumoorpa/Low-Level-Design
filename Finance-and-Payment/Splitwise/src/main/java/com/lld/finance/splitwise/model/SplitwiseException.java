package com.lld.finance.splitwise.model;

/** Invalid split, unknown user or group, non-member, unsettled balance... */
public class SplitwiseException extends RuntimeException {

    public SplitwiseException(String message) {
        super(message);
    }
}
