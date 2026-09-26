package com.lld.finance.exchange.core;

import com.lld.finance.exchange.model.Trade;

/** Observer: market-data feed (last price, tickers), trade reports, clearing. */
@FunctionalInterface
public interface TradeListener {

    void onTrade(Trade trade);
}
