package com.example.travel.invoice;

import java.util.ArrayList;
import java.util.List;

import com.example.travel.api.dto.FeeLineResponse;
import com.example.travel.api.dto.TaxLineResponse;
import com.example.travel.domain.Money;
import com.example.travel.invoice.InvoiceSnapshot.SnapshotItem;
import com.example.travel.transaction.StoredTransaction;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the frozen snapshot from fully loaded transactions, in request order:
 * totals summed with {@link Math#addExact} on minor units, rollups accumulated
 * in sorted maps, and tax/fee lines serialized to JSON per item so GET never
 * needs the live transaction rows.
 */
@Component
public class InvoiceSnapshotBuilder {

    private final ObjectMapper objectMapper;

    public InvoiceSnapshotBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public InvoiceSnapshot build(List<StoredTransaction> requestOrder) {
        List<SnapshotItem> items = new ArrayList<>(requestOrder.size());
        RollupAccumulator rollups = new RollupAccumulator();
        long totalMinor = 0;

        for (int position = 0; position < requestOrder.size(); position++) {
            StoredTransaction transaction = requestOrder.get(position);
            totalMinor = Math.addExact(totalMinor, transaction.totalMinor());
            transaction.taxLines().forEach(line ->
                    rollups.addTax(line.name(), line.rate(), line.amountMinor()));
            transaction.feeLines().forEach(line ->
                    rollups.addFee(line.name(), line.amountMinor()));
            items.add(new SnapshotItem(position, transaction,
                    taxLinesJson(transaction), feeLinesJson(transaction)));
        }

        String currency = requestOrder.isEmpty() ? null : requestOrder.getFirst().currency();
        return new InvoiceSnapshot(null, currency, totalMinor, List.copyOf(items),
                rollups.taxLines(), rollups.feeLines());
    }

    private String taxLinesJson(StoredTransaction transaction) {
        return objectMapper.writeValueAsString(transaction.taxLines().stream()
                .map(line -> new TaxLineResponse(line.name(), line.rate(), Money.ofMinor(line.amountMinor()).toDecimal()))
                .toList());
    }

    private String feeLinesJson(StoredTransaction transaction) {
        return objectMapper.writeValueAsString(transaction.feeLines().stream()
                .map(line -> new FeeLineResponse(line.name(), Money.ofMinor(line.amountMinor()).toDecimal()))
                .toList());
    }
}
