package com.example.travel.api.dto;

import java.math.BigDecimal;

import com.example.travel.common.Decimals;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Invoice-level tax rollup, grouped by (name, rate); stored at write time. */
public record TaxRollupResponse(String name,
                                @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal rate,
                                BigDecimal amount) {

    /** Echo the grouped rate without trailing zeros (0.0700 -> 0.07). */
    public TaxRollupResponse {
        rate = Decimals.normalizedRate(rate);
    }
}
