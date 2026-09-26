package com.lld.finance.payments.core;

import com.lld.finance.payments.model.Instrument;
import com.lld.finance.payments.model.PaymentException;
import com.lld.finance.payments.processor.ProcessorClient.CardData;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Tokenization. The only component that ever stores card numbers (in real life: an isolated,
 * encrypted, PCI-DSS audited service). Everything else holds a token. The same card always gets the
 * same token, which also lets risk rules count attempts per card.
 */
public final class CardVault {

    private final Map<String, String> tokenByPan = new HashMap<>();
    private final Map<String, CardData> dataByToken = new HashMap<>();
    private final Clock clock;
    private long seq;

    public CardVault(Clock clock) {
        this.clock = clock;
    }

    public synchronized Instrument.Card tokenize(String pan, int expMonth, int expYear) {
        String digits = pan == null ? "" : pan.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{12,19}") || !luhnValid(digits)) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST, "Invalid card number");
        }
        if (expMonth < 1 || expMonth > 12) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST, "Invalid expiry month");
        }
        if (YearMonth.of(expYear, expMonth).isBefore(YearMonth.now(clock.withZone(ZoneOffset.UTC)))) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST, "Card expired");
        }
        String brand = brand(digits);
        String token = tokenByPan.computeIfAbsent(digits, p -> "tok_" + (++seq));
        dataByToken.put(token, new CardData(digits, expMonth, expYear));
        return new Instrument.Card(token, brand, digits.substring(digits.length() - 4), expMonth, expYear);
    }

    synchronized CardData lookup(String token) {
        CardData d = dataByToken.get(token);
        if (d == null) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST, "Unknown card token");
        }
        return d;
    }

    /** Luhn checksum: catches almost every single-digit typo and adjacent swap. */
    public static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    static String brand(String pan) {
        if (pan.startsWith("4")) {
            return "VISA";
        }
        int two = Integer.parseInt(pan.substring(0, 2));
        if (two >= 51 && two <= 55) {
            return "MASTERCARD";
        }
        if (two == 34 || two == 37) {
            return "AMEX";
        }
        return "OTHER";
    }
}
