package com.example.travel.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** A frozen snapshot of one transaction on an invoice (not a live join). */
public record InvoiceItemResponse(
        int position,
        String transactionId,
        String type,
        String externalId,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        List<TaxLineResponse> taxLines,
        List<FeeLineResponse> feeLines,
        JsonNode metadata) {
}
