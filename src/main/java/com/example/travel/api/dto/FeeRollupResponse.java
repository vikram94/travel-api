package com.example.travel.api.dto;

import java.math.BigDecimal;

/** Invoice-level fee rollup (from fee_lines only), grouped by name; stored at write time. */
public record FeeRollupResponse(String name, BigDecimal amount) {
}
