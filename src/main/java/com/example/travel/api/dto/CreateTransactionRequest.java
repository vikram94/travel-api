package com.example.travel.api.dto;

import java.math.BigDecimal;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * POST /transactions body. Field names are snake_case via the global
 * Jackson naming strategy (external_id, tax_lines, fee_lines, occurred_at).
 */
public record CreateTransactionRequest(
        String type,
        String externalId,
        String currency,
        String occurredAt,
        BigDecimal total,
        List<TaxLineRequest> taxLines,
        List<FeeLineRequest> feeLines,
        JsonNode metadata) {
}
