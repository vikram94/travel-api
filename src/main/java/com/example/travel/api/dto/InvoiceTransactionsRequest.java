package com.example.travel.api.dto;

import java.util.List;

/**
 * Body of BOTH POST /invoices and PATCH /invoices/{id}: exactly one of
 * transaction_id or transaction_ids. PATCH replaces the whole set, so it shares
 * the create shape (hence the neutral name rather than "Create...").
 */
public record InvoiceTransactionsRequest(String transactionId, List<String> transactionIds) {
}
