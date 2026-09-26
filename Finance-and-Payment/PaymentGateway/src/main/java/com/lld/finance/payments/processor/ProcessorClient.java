package com.lld.finance.payments.processor;

import com.lld.finance.payments.model.Instrument;
import com.lld.finance.payments.model.Money;

/**
 * Adapter over one acquirer / payment processor API. The gateway talks to several (for cost, card
 * brand coverage and redundancy) through this one interface.
 *
 * <p>Two kinds of "no": a <b>decline</b> (the bank said no; a normal {@link AuthResponse}) versus
 * {@link ProcessorUnavailable} (timeout, 5xx: we don't know, try another processor).
 */
public interface ProcessorClient {

    String id();

    boolean supports(Instrument instrument);

    /** What this processor charges us, used to prefer the cheapest route. */
    int costBasisPoints();

    /** @param card full card data from the vault, or null for non-card instruments */
    AuthResponse authorize(String paymentId, Money amount, Instrument instrument, CardData card) throws ProcessorUnavailable;

    String capture(String authReference, Money amount) throws ProcessorUnavailable;

    String refund(String authReference, Money amount) throws ProcessorUnavailable;

    void voidAuthorization(String authReference) throws ProcessorUnavailable;

    record AuthResponse(boolean approved, String reference, String declineReason) {

        public static AuthResponse approved(String reference) {
            return new AuthResponse(true, reference, null);
        }

        public static AuthResponse declined(String reason) {
            return new AuthResponse(false, null, reason);
        }
    }

    /** Sensitive card data, only ever held in memory between the vault and a processor call. */
    record CardData(String pan, int expMonth, int expYear) {
        @Override
        public String toString() {
            return "CardData[****]";                             // never log a card number
        }
    }
}
