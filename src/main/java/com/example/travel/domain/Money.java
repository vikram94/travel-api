package com.example.travel.domain;

import java.math.BigDecimal;

/**
 * A monetary amount in integer minor units, scale 2 (e.g. cents).
 * IEEE floats are never used for money; the API layer converts
 * decimal amounts via {@link #fromDecimal} and renders via {@link #toDecimal}.
 */
public record Money(long minor) {

    private static final int MINOR_UNITS = 2;

    /**
     * Largest number of digits tolerated left of the decimal point. Bounding this
     * BEFORE any scaling is what turns a hostile exponent such as {@code 1E+999999999}
     * into a 400: its negative scale would slip past a "more than 2 decimals" check
     * and then blow up {@code movePointRight} with an ArithmeticException.
     */
    private static final int MAX_INTEGER_DIGITS = 18;

    /**
     * Parses an API decimal amount into minor units.
     * 400s when negative, more than 2 (significant) decimal places, or out of range.
     */
    public static Money fromDecimal(BigDecimal amount, String field) {
        if (amount == null) {
            throw new ValidationException(field, "is required");
        }
        if (amount.signum() < 0) {
            throw new ValidationException(field, "must be >= 0");
        }
        if (amount.signum() == 0) {
            return new Money(0);
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        // (long) keeps precision() - scale() from overflowing int for extreme exponents
        if ((long) normalized.precision() - normalized.scale() > MAX_INTEGER_DIGITS) {
            throw new ValidationException(field, "amount is out of range");
        }
        if (normalized.scale() > MINOR_UNITS) {
            throw new ValidationException(field, "must have at most 2 decimal places");
        }
        try {
            return new Money(normalized.movePointRight(MINOR_UNITS).longValueExact());
        } catch (ArithmeticException e) {
            throw new ValidationException(field, "amount is out of range");
        }
    }

    public static Money ofMinor(long minor) {
        return new Money(minor);
    }

    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minor, MINOR_UNITS);
    }

    /** Exact addition; overflows throw {@link ArithmeticException} (callers map to 400). */
    public Money plus(Money other) {
        return new Money(Math.addExact(this.minor, other.minor));
    }
}
