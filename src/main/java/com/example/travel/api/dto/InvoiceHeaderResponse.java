package com.example.travel.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Invoice header without items/rollups — used by the list endpoint. */
public record InvoiceHeaderResponse(
        String id,
        String currency,
        BigDecimal total,
        int itemCount,
        String txnSetHash,
        Instant createdAt) {
}
