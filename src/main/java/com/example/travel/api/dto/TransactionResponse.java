package com.example.travel.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Stored transaction as returned by POST/GET/PATCH. */
public record TransactionResponse(
        String id,
        String type,
        String externalId,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        List<TaxLineResponse> taxLines,
        List<FeeLineResponse> feeLines,
        JsonNode metadata,
        Instant createdAt) {
}
