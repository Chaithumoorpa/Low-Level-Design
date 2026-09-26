package com.lld.states.atm.machine;

import com.lld.states.atm.model.Card;
import com.lld.states.atm.model.TransactionRecord;

/** Observer: screens, receipt printers, bank monitoring and fraud alerts subscribe here. */
public interface AtmEventListener {

    default void onStateChange(AtmStatus from, AtmStatus to) {
    }

    default void onTransaction(TransactionRecord record) {
    }

    default void onCardRetained(Card card) {
    }
}
