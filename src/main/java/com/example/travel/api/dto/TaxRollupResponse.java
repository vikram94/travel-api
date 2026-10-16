package com.example.travel.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Invoice-level tax rollup, grouped by (name, rate); stored at write time. */
public record TaxRollupResponse(String name,
                                @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal rate,
                                BigDecimal amount) {
}
