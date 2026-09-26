package com.lld.finance.payments.model;

/**
 * How the customer pays. A card is only ever a <b>token</b> plus harmless display data (brand, last 4):
 * the full card number lives in the vault, so payments, logs and merchants never see it.
 */
public sealed interface Instrument {

    String fingerprint();

    record Card(String token, String brand, String last4, int expMonth, int expYear) implements Instrument {
        @Override
        public String fingerprint() {
            return token;
        }

        @Override
        public String toString() {
            return brand + " **** " + last4;
        }
    }

    record Upi(String vpa) implements Instrument {
        @Override
        public String fingerprint() {
            return "upi:" + vpa;
        }

        @Override
        public String toString() {
            return "UPI " + vpa;
        }
    }
}
