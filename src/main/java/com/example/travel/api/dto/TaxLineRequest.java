package com.example.travel.api.dto;

import java.math.BigDecimal;

/** A government/VAT-style tax line; rate is optional and echo-only. */
public record TaxLineRequest(String name, BigDecimal rate, BigDecimal amount) {
}
