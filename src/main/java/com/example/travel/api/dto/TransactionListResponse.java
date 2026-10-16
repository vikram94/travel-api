package com.example.travel.api.dto;

import java.util.List;

/** Keyset page of transactions; next_cursor is null on the last page. */
public record TransactionListResponse(List<TransactionResponse> items, String nextCursor) {
}
