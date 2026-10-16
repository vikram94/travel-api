package com.example.travel.api.dto;

import java.util.List;

/** Keyset page of invoice headers; next_cursor is null on the last page. */
public record InvoiceListResponse(List<InvoiceHeaderResponse> items, String nextCursor) {
}
