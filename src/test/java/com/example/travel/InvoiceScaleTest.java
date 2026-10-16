package com.example.travel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.travel.domain.FeeKind;
import com.example.travel.domain.FeeLine;
import com.example.travel.domain.TaxLine;
import com.example.travel.domain.TransactionType;
import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import com.example.travel.transaction.StoredTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan test 14: the 1000-row ceiling. Seeds 1000 transactions through the
 * repository, invoices exactly 1000 of them in one request, checks every
 * rollup against an independently accumulated sum, and walks the frozen item
 * list with the items cursor (500 + 500). Durations are logged, not asserted,
 * so the test documents cost without being flaky on slow machines.
 */
class InvoiceScaleTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("invoice-scale");
    private static final int COUNT = 1000;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @Test
    void thousandTransactionInvoiceIsAcceptedAndPaginatesEntirely() {
        long seedStart = System.nanoTime();
        List<StoredTransaction> seeded = seedTransactions(COUNT);
        long seedMillis = (System.nanoTime() - seedStart) / 1_000_000;

        List<String> ids = seeded.stream().map(StoredTransaction::id).toList();

        long createStart = System.nanoTime();
        HttpResult created = post("/invoices", Map.of("transaction_ids", ids));
        long createMillis = (System.nanoTime() - createStart) / 1_000_000;

        System.out.printf("[scale] seeded %d txns in %d ms; created invoice in %d ms%n",
                COUNT, seedMillis, createMillis);

        assertThat(created.status()).as("create body: %s", created.body()).isEqualTo(201);
        JsonNode invoice = created.body();
        String invoiceId = created.id();

        assertThat(invoice.get("item_count").asInt()).isEqualTo(COUNT);
        assertThat(invoice.get("currency").asText()).isEqualTo("EUR");
        assertThat(invoice.get("txn_set_hash").asText()).hasSize(64);

        // ---- total: independent sum over the seeded rows
        long expectedGrandMinor = seeded.stream().mapToLong(StoredTransaction::totalMinor).sum();
        assertThat(minor(invoice.get("total"))).isEqualTo(expectedGrandMinor);

        // ---- rollups: independently accumulated from what was seeded
        Map<String, Long> expectedTaxes = new LinkedHashMap<>();
        Map<String, Long> expectedFees = new LinkedHashMap<>();
        for (StoredTransaction transaction : seeded) {
            transaction.taxLines().forEach(line ->
                    expectedTaxes.merge(line.name(), line.amountMinor(), Long::sum));
            transaction.feeLines().forEach(line ->
                    expectedFees.merge(line.name(), line.amountMinor(), Long::sum));
        }
        assertThat(rollupAmounts(invoice.get("tax_rollup"))).isEqualTo(expectedTaxes);
        assertThat(rollupAmounts(invoice.get("fee_rollup"))).isEqualTo(expectedFees);
        // exact numbers, spelled out: 200 flights x 50c, 200 hotels x 30c...
        assertThat(expectedTaxes).containsExactlyInAnyOrderEntriesOf(
                Map.of("Airport tax", 200 * 50L, "VAT", 200 * 30L));
        assertThat(expectedFees).containsExactlyInAnyOrderEntriesOf(
                Map.of("YQ", 200 * 25L, "Booking fee", 200 * 40L));
        assertThat(expectedGrandMinor).isEqualTo(10_499_500L); // 104,995.00 EUR

        // ---- the create response embeds the first page of items (default 100)
        assertThat(invoice.get("items")).hasSize(100);
        assertThat(invoice.get("items_next_cursor").isNull()).isFalse();

        // ---- walk the frozen item list: 500 + 500, then the cursor is exhausted
        Set<String> seenTransactionIds = new HashSet<>();
        Set<Integer> seenPositions = new HashSet<>();
        String cursor = null;
        int pages = 0;
        long readStart = System.nanoTime();
        while (true) {
            String query = "?items_limit=500" + (cursor == null ? "" : "&items_cursor=" + cursor);
            JsonNode page = get("/invoices/" + invoiceId + query).body();
            List<JsonNode> items = new ArrayList<>();
            page.get("items").forEach(items::add);
            assertThat(items).isNotEmpty();
            for (JsonNode item : items) {
                assertThat(seenTransactionIds.add(item.get("transaction_id").asText()))
                        .as("duplicate item for transaction %s", item.get("transaction_id").asText())
                        .isTrue();
                assertThat(seenPositions.add(item.get("position").asInt()))
                        .as("duplicate position %s", item.get("position").asInt())
                        .isTrue();
            }
            pages++;
            if (page.get("items_next_cursor").isNull()) {
                break;
            }
            cursor = page.get("items_next_cursor").asText();
            assertThat(pages).isLessThan(10); // guard against a cursor that never ends
        }
        long readMillis = (System.nanoTime() - readStart) / 1_000_000;
        System.out.printf("[scale] walked %d items in %d pages in %d ms%n",
                seenTransactionIds.size(), pages, readMillis);

        assertThat(seenTransactionIds).hasSameSizeAs(ids).containsAll(ids);
        assertThat(seenPositions).hasSize(COUNT).contains(0, COUNT - 1);

        // ---- the frozen snapshot is served straight from storage: still intact
        JsonNode reread = get("/invoices/" + invoiceId).body();
        assertThat(reread.get("item_count").asInt()).isEqualTo(COUNT);
        assertThat(minor(reread.get("total"))).isEqualTo(expectedGrandMinor);
        assertThat(transactionRepository.countAll()).isEqualTo(COUNT);
    }

    // ------------------------------------------------------------ helpers

    /**
     * One transaction per index, five rotating shapes so every rollup path and
     * every transaction type is present exactly 200 times. Money is minor units
     * so the expected total is exact without any rounding assumption.
     */
    private List<StoredTransaction> seedTransactions(int count) {
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        List<StoredTransaction> transactions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TransactionType type = switch (i % 5) {
                case 0 -> TransactionType.FLIGHT;
                case 1 -> TransactionType.HOTEL;
                case 2 -> TransactionType.RAIL;
                default -> TransactionType.NAVAN_FEE;
            };
            FeeKind feeKind = i % 5 == 3 ? FeeKind.TRIP_FEE
                    : i % 5 == 4 ? FeeKind.AGENT_CALL_FEE
                    : null;
            List<TaxLine> taxes = switch (i % 5) {
                case 0 -> List.of(new TaxLine("Airport tax", null, 50L));
                case 1 -> List.of(new TaxLine("VAT", new BigDecimal("0.0700"), 30L));
                default -> List.of();
            };
            List<FeeLine> fees = switch (i % 5) {
                case 0 -> List.of(new FeeLine("YQ", 25L));
                case 2 -> List.of(new FeeLine("Booking fee", 40L));
                default -> List.of();
            };
            transactions.add(new StoredTransaction(
                    "txn_scale_%04d".formatted(i),
                    "scale-%04d".formatted(i),
                    type,
                    "EUR",
                    base.plusSeconds(i),
                    10_000L + i,
                    feeKind,
                    objectMapper.createObjectNode(),
                    base.plusSeconds(i),
                    taxes,
                    fees));
        }
        transactionRepository.insertAll(transactions);
        return transactions;
    }

    /** Decimal amount from the wire back to minor units without float math. */
    private static long minor(JsonNode amount) {
        return amount.decimalValue().movePointRight(2).longValueExact();
    }

    private static Map<String, Long> rollupAmounts(JsonNode rollup) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        rollup.forEach(entry -> amounts.put(entry.get("name").asText(), minor(entry.get("amount"))));
        return amounts;
    }
}
