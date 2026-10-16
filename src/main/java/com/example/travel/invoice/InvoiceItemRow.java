package com.example.travel.invoice;

import java.time.LocalDateTime;

/** One frozen invoice line as stored. */
record InvoiceItemRow(
        int position,
        String transactionId,
        String externalId,
        String type,
        String currency,
        LocalDateTime occurredAt,
        long totalMinor,
        String feeKind,
        String metadataSnapshot,
        String taxLinesSnapshot,
        String feeLinesSnapshot) {
}
