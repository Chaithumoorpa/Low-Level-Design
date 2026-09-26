package com.lld.finance.payments.model;

/** An amount in minor units (cents, paise) of one currency. Mixing currencies is an error, never a conversion. */
public record Money(long minor, String currency) implements Comparable<Money> {

    public Money {
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("ISO currency code required");
        }
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    public Money plus(Money o) {
        same(o);
        return new Money(Math.addExact(minor, o.minor), currency);
    }

    public Money minus(Money o) {
        same(o);
        return new Money(Math.subtractExact(minor, o.minor), currency);
    }

    public boolean isPositive() {
        return minor > 0;
    }

    @Override
    public int compareTo(Money o) {
        same(o);
        return Long.compare(minor, o.minor);
    }

    private void same(Money o) {
        if (!currency.equals(o.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + o.currency);
        }
    }

    @Override
    public String toString() {
        return String.format("%s %d.%02d", currency, minor / 100, Math.abs(minor % 100));
    }
}
