package com.example.travel.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A monetary amount in integer minor units, scale 2 (e.g. cents).
 * IEEE floats are never used for money; the API layer converts
 * decimal amounts via {@link #fromDecimal} and renders via {@link #toDecimal}.
 */
public record Money(long minor) {

    private static final int MINOR_UNITS = 2;

    /**
     * Parses an API decimal amount into minor units.
     * 400s when negative, more than 2 decimal places, or out of long range.
     */
    public static Money fromDecimal(BigDecimal amount, String field) {
        if (amount == null) {
            throw new ValidationException(field, "is required");
        }
        if (amount.signum() < 0) {
            throw new ValidationException(field, "must be >= 0");
        }
        BigDecimal shifted = amount.movePointRight(MINOR_UNITS);
        if (shifted.stripTrailingZeros().scale() > 0) {
            throw new ValidationException(field, "must have at most 2 decimal places");
        }
        try {
            return new Money(shifted.longValueExact());
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
