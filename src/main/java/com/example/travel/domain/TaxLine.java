package com.example.travel.domain;

import java.math.BigDecimal;

/** Parsed tax line: rate is echo-only, normalized to scale 4 (nullable). */
public record TaxLine(String name, BigDecimal rate, long amountMinor) {
}
