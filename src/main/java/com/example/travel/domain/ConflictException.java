package com.example.travel.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 409 with a specific error code and extra keys, e.g.
 * { error: "duplicate_external_id", message, existing_id }.
 */
public class ConflictException extends RuntimeException {

    private final String error;
    private final Map<String, Object> details;

    public ConflictException(String error, String message, Map<String, Object> details) {
        super(message);
        this.error = error;
        this.details = Map.copyOf(details);
    }

    public String error() {
        return error;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ConflictException duplicateExternalId(String existingId) {
        return new ConflictException("duplicate_external_id",
                "a transaction with this external_id already exists",
                Map.of("existing_id", existingId));
    }

    public static ConflictException invoiceExistsForSet(String existingInvoiceId) {
        return new ConflictException("invoice_exists_for_set",
                "an invoice already exists for this set of transactions",
                Map.of("existing_invoice_id", existingInvoiceId));
    }

    public static ConflictException mixedCurrency(Collection<String> currencies) {
        return new ConflictException("mixed_currency",
                "all transactions on one invoice must share a single currency",
                Map.of("currencies", List.copyOf(new TreeSet<>(currencies))));
    }

    public static ConflictException duplicateTransactionId(List<String> repeatedIds) {
        return new ConflictException("duplicate_transaction_id",
                "the same transaction id appears more than once in the request",
                Map.of("ids", repeatedIds));
    }

    public static ConflictException transactionInvoiced(String transactionId) {
        return new ConflictException("transaction_invoiced",
                "this transaction is on an invoice; billed transactions are frozen",
                Map.of("transaction_id", transactionId));
    }
}
