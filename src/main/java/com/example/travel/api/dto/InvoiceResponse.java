package com.example.travel.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Invoice response: header + full rollups always, plus one page of items.
 * POST/PATCH return the first page built in memory (no re-read);
 * GET /invoices/{id}?items_limit=&items_cursor= selects the page.
 */
public record InvoiceResponse(
        String id,
        String currency,
        BigDecimal total,
        int itemCount,
        Instant createdAt,
        Instant updatedAt,
        List<TaxRollupResponse> taxRollup,
        List<FeeRollupResponse> feeRollup,
        List<InvoiceItemResponse> items,
        String itemsNextCursor) {
}
