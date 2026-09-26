package com.lld.finance.exchange.model;

/**
 * What happens to the part that can't trade immediately.
 * GTC: good till cancelled (rests on the book). IOC: immediate or cancel (fill what you can, cancel the
 * rest). FOK: fill or kill (all of it right now, or nothing at all).
 */
public enum TimeInForce {
    GTC, IOC, FOK
}
