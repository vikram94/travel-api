package com.example.travel.common;

import java.math.BigDecimal;

/**
 * Presentation helpers for exact decimals. Amounts always go through
 * {@link com.example.travel.domain.Money} (scale 2); these cover the
 * non-money decimals the API echoes back verbatim.
 */
public final class Decimals {

    private Decimals() {
    }

    /**
     * Echoes a tax rate without trailing zeros: {@code 0.0700} -> {@code 0.07},
     * {@code 1.0000} -> {@code 1}. Negative scales are flattened to plain form so
     * a whole rate never serializes as {@code 1E+0}.
     */
    public static BigDecimal normalizedRate(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
