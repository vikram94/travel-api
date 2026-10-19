package com.example.travel.invoice;

import java.time.LocalDateTime;

/**
 * Invoice header row (no items/rollups). The internal {@code txn_set_hash}
 * fingerprint stays in the DB as the uniqueness guard but is not part of this
 * read model — it is an implementation detail and is not exposed on the API.
 */
record InvoiceHeader(
        String id,
        String currency,
        long totalMinor,
        int itemCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
