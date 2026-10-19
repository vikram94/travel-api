package com.example.travel.api.dto;

import java.math.BigDecimal;

import com.example.travel.common.Decimals;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A government/VAT-style tax line; rate is optional and echo-only. */
public record TaxLineResponse(String name,
                              @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal rate,
                              BigDecimal amount) {

    /** Echo the rate without trailing zeros (0.0700 -> 0.07) on every read path. */
    public TaxLineResponse {
        rate = Decimals.normalizedRate(rate);
    }
}
