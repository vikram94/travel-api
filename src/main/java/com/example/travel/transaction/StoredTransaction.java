package com.example.travel.transaction;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.travel.domain.FeeKind;
import com.example.travel.domain.FeeLine;
import com.example.travel.domain.TaxLine;
import com.example.travel.domain.TransactionType;
import tools.jackson.databind.JsonNode;

/**
 * A transaction exactly as stored: header + tax lines + fee lines.
 * Money is minor units; occurredAt/createdAt are UTC truncated to micros.
 */
public record StoredTransaction(
        String id,
        String externalId,
        TransactionType type,
        String currency,
        LocalDateTime occurredAt,
        long totalMinor,
        FeeKind feeKind,
        JsonNode metadata,
        LocalDateTime createdAt,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines) {

    public StoredTransaction withLines(List<TaxLine> taxLines, List<FeeLine> feeLines) {
        return new StoredTransaction(id, externalId, type, currency, occurredAt, totalMinor, feeKind,
                metadata, createdAt, taxLines, feeLines);
    }
}
