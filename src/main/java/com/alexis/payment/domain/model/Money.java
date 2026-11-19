package com.alexis.payment.domain.model;

import java.util.Currency;
import java.util.Objects;

/** Monetary amount in minor units (cents) + ISO-4217 currency. Never float/double. */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must be >= 0");
        }
    }

    /** @throws IllegalArgumentException if the ISO-4217 code is unknown */
    public static Money of(long amountMinor, String isoCurrency) {
        return new Money(amountMinor, Currency.getInstance(isoCurrency));
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amountMinor > other.amountMinor;
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amountMinor + " " + currency.getCurrencyCode();
    }
}
