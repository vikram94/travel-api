package com.example.travel.invoice;

import java.time.LocalDateTime;

/** Invoice header row (no items/rollups). */
record InvoiceHeader(
        String id,
        String currency,
        long totalMinor,
        int itemCount,
        String txnSetHash,
        LocalDateTime createdAt) {
}
