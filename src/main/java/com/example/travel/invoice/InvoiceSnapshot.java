package com.example.travel.invoice;

import java.util.List;

import com.example.travel.transaction.StoredTransaction;

/**
 * The frozen content of one invoice: items in request order plus pre-computed
 * rollups. Built entirely in memory from locked transactions, then written in
 * one transaction (header + items + rollups).
 */
record InvoiceSnapshot(
        String invoiceId,
        String currency,
        long totalMinor,
        List<SnapshotItem> items,
        List<RollupAccumulator.TaxRollupLine> taxRollup,
        List<RollupAccumulator.FeeRollupLine> feeRollup) {

    /** One frozen line: the transaction as it was, plus its serialized line snapshots. */
    record SnapshotItem(int position, StoredTransaction transaction, String taxLinesJson, String feeLinesJson) {
    }

    InvoiceSnapshot withId(String id) {
        return new InvoiceSnapshot(id, currency, totalMinor, items, taxRollup, feeRollup);
    }
}
