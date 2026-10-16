package com.example.travel.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A government/VAT-style tax line; rate is optional and echo-only. */
public record TaxLineResponse(String name,
                              @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal rate,
                              BigDecimal amount) {
}
