package com.example.travel.api.dto;

import java.util.List;

/**
 * POST/PATCH /invoices body: exactly one of transaction_id or transaction_ids.
 */
public record CreateInvoiceRequest(String transactionId, List<String> transactionIds) {
}
