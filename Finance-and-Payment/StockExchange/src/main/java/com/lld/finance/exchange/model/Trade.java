package com.lld.finance.exchange.model;

import java.time.Instant;

/**
 * An execution. Price is always the <b>resting</b> order's price: the order that was waiting set the
 * price, the incoming (aggressor) order accepted it.
 */
public record Trade(long id, String symbol, long price, long quantity, String buyOrderId, String sellOrderId,
                    String buyerId, String sellerId, Side aggressor, Instant at) {

    @Override
    public String toString() {
        return String.format("T%d %s %d @ %s (buyer %s, seller %s, aggressor %s)", id, symbol, quantity,
                Prices.format(price), buyerId, sellerId, aggressor);
    }
}
