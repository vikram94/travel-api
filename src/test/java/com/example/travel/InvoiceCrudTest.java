package com.example.travel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan tests 3 (trip invoice matches expected-invoice.json), 4 (snapshot frozen
 * against later writes), 5 (mixed currency), 9 (DELETE invoice keeps
 * transactions), 11 (fingerprint 409s / hash freed by DELETE), 13 (PATCH
 * invoice rebuild + refusals), 15 (cap, repeated and missing ids), plus GET
 * paging over items and rollups.
 */
class InvoiceCrudTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("invoice-crud");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    // ------------------------------------------------------- plan test 3

    @Test
    void tripInvoiceTotalsAndRollupsMatchTheExpectedFixture() {
        List<String> trip = createTrip();

        HttpResult created = post("/invoices", multiInvoiceBody(trip));
        assertThat(created.status()).isEqualTo(201);

        var invoice = get("/invoices/" + created.id()).body();
        assertThat(invoice.get("currency").asText())
                .isEqualTo(EXPECTED.get("currency").asText());
        assertThat(invoice.get("item_count").asInt())
                .isEqualTo(5);
        assertThat(invoice.get("total").decimalValue())
                .isEqualByComparingTo(EXPECTED.get("totals").get("grand_total").decimalValue());

        // per-type contributions
        Map<String, String> totals = Map.of(
                "flight", EXPECTED.get("totals").get("flight").asText(),
                "hotel", EXPECTED.get("totals").get("hotel").asText(),
                "rail", EXPECTED.get("totals").get("rail").asText());
        Set<String> feeKinds = new HashSet<>();
        Set<String> types = new HashSet<>();
        var items = invoice.get("items");
        assertThat(items).hasSize(5);
        for (var item : items) {
            types.add(item.get("type").asText());
            String type = item.get("type").asText();
            if (totals.containsKey(type)) {
                assertThat(item.get("total").decimalValue())
                        .as("total of %s", type)
                        .isEqualByComparingTo(totals.get(type));
            }
            if ("navan_fee".equals(type)) {
                String feeKind = item.get("metadata").get("fee_kind").asText();
                feeKinds.add(feeKind);
                String expectedFeeTotal = "trip_fee".equals(feeKind)
                        ? EXPECTED.get("totals").get("trip_fee").asText()
                        : EXPECTED.get("totals").get("agent_call_fee").asText();
                assertThat(item.get("total").decimalValue()).isEqualByComparingTo(expectedFeeTotal);
            }
            // metadata round-trips, not an empty object
            assertThat(item.get("metadata").size()).isGreaterThan(0);
        }
        assertThat(types).containsExactlyInAnyOrderElementsOf(
                listOfStrings(EXPECTED.get("transaction_types_included")));
        assertThat(feeKinds).containsExactlyInAnyOrder("trip_fee", "agent_call_fee");

        // tax rollup: Airport tax 15 + VAT 0.07/21.70
        var taxRollup = invoice.get("tax_rollup");
        assertThat(taxRollup).hasSize(2);
        assertThat(findByName(taxRollup, "Airport tax").get("amount").decimalValue()).isEqualByComparingTo("15");
        assertThat(findByName(taxRollup, "VAT").get("amount").decimalValue()).isEqualByComparingTo("21.70");
        assertThat(findByName(taxRollup, "VAT").get("rate").decimalValue()).isEqualByComparingTo("0.07");

        // fee rollup from fee_lines only: YQ 25 + Booking fee 4
        var feeRollup = invoice.get("fee_rollup");
        assertThat(feeRollup).hasSize(2);
        assertThat(findByName(feeRollup, "YQ").get("amount").decimalValue()).isEqualByComparingTo("25");
        assertThat(findByName(feeRollup, "Booking fee").get("amount").decimalValue()).isEqualByComparingTo("4");

        // structural metadata checks from the fixture
        var flightItem = itemByType(items, "flight");
        assertThat(flightItem.get("metadata").get("coupons").get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertThat(itemByType(items, "hotel").get("metadata").get("address").has("street")).isTrue();
        assertThat(itemByType(items, "rail").get("metadata").get("legs").size()).isEqualTo(2);
    }

    // ------------------------------------------------------- plan test 4

    @Test
    void invoiceStaysFrozenEvenIfTheStoredTransactionLaterChanges() {
        List<String> trip = createTrip();
        String flightId = trip.getFirst();
        HttpResult created = post("/invoices", multiInvoiceBody(trip));
        assertThat(created.status()).isEqualTo(201);

        // API guard: PATCH is refused outright
        Map<String, Object> patchBody = seed("flight");
        patchBody.put("total", 999.99);
        assertThat(patch("/transactions/" + flightId, patchBody).status()).isEqualTo(409);

        // belt and braces: even a write that bypasses the API guard cannot move the invoice
        var stored = get("/transactions/" + flightId).body();
        transactionRepository.replaceContent(flightId,
                java.time.LocalDateTime.parse("2026-03-11T06:40:00"),
                50000L,
                objectMapper.writeValueAsString(stored.get("metadata")),
                null, List.of(), List.of());

        assertThat(get("/transactions/" + flightId).body().get("total").decimalValue())
                .isEqualByComparingTo("500.00");
        var invoice = get("/invoices/" + created.id()).body();
        assertThat(itemByType(invoice.get("items"), "flight").get("total").decimalValue())
                .isEqualByComparingTo("220.00");
        assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo("685.70");
    }

    // ------------------------------------------------------- plan test 5

    @Test
    void mixedCurrencyInvoiceIs409() {
        String eurId = post("/transactions", seed("flight")).id();
        Map<String, Object> usd = uniqueSeed("hotel");
        usd.put("currency", "USD");
        String usdId = post("/transactions", usd).id();

        HttpResult created = post("/invoices", multiInvoiceBody(List.of(eurId, usdId)));
        assertThat(created.status()).isEqualTo(409);
        assertThat(created.error()).isEqualTo("mixed_currency");
        assertThat(created.body().get("currencies")).isNotNull();
        assertThat(invoiceRepository.countAll()).isZero();
    }

    // ------------------------------------------------------- plan test 9

    @Test
    void deleteInvoiceKeepsTransactionsAndFreesTheSetHash() {
        List<String> trip = createTrip();
        HttpResult created = post("/invoices", multiInvoiceBody(trip));
        assertThat(created.status()).isEqualTo(201);
        String hash = created.body().get("txn_set_hash").asText();

        assertThat(delete("/invoices/" + created.id()).status()).isEqualTo(204);
        assertThat(get("/invoices/" + created.id()).status()).isEqualTo(404);
        assertThat(invoiceRepository.countAll()).isZero();

        // every transaction still reads back untouched
        for (String id : trip) {
            assertThat(get("/transactions/" + id).status()).isEqualTo(200);
        }
        assertThat(transactionRepository.countAll()).isEqualTo(5);

        // the fingerprint was freed: the same set can be invoiced again
        HttpResult again = post("/invoices", multiInvoiceBody(trip));
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body().get("txn_set_hash").asText()).isEqualTo(hash);
    }

    // ------------------------------------------------------ plan test 11

    @Test
    void identicalSetIsRejectedWith409UntilItIsDeleted() {
        List<String> trip = createTrip();

        HttpResult first = post("/invoices", multiInvoiceBody(trip));
        assertThat(first.status()).isEqualTo(201);

        // same set again -> 409 with the first invoice id
        HttpResult same = post("/invoices", multiInvoiceBody(trip));
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.error()).isEqualTo("invoice_exists_for_set");
        assertThat(same.body().get("existing_invoice_id").asText()).isEqualTo(first.id());

        // reversed order is the same set
        List<String> reversed = new ArrayList<>(trip);
        java.util.Collections.reverse(reversed);
        HttpResult reversedResult = post("/invoices", multiInvoiceBody(reversed));
        assertThat(reversedResult.status()).isEqualTo(409);
        assertThat(reversedResult.body().get("existing_invoice_id").asText()).isEqualTo(first.id());

        // a subset is a different set -> 201
        HttpResult subset = post("/invoices", multiInvoiceBody(List.of(trip.getFirst(), trip.get(1))));
        assertThat(subset.status()).isEqualTo(201);

        // after DELETE the same set can be issued again
        assertThat(delete("/invoices/" + first.id()).status()).isEqualTo(204);
        HttpResult reissued = post("/invoices", multiInvoiceBody(trip));
        assertThat(reissued.status()).isEqualTo(201);
        assertThat(invoiceRepository.countAll()).isEqualTo(2);
    }

    // ------------------------------------------------------ plan test 13

    @Test
    void patchInvoiceRebuildsSnapshotAndRefusalsLeaveItUnchanged() {
        List<String> trip = createTrip();
        HttpResult invoiceA = post("/invoices", multiInvoiceBody(trip));
        assertThat(invoiceA.status()).isEqualTo(201);
        String originalHash = invoiceA.body().get("txn_set_hash").asText();

        String x = post("/transactions", uniqueSeed("flight")).id();
        String y = post("/transactions", uniqueSeed("rail")).id();
        String usd = post("/transactions", usdSeed()).id();

        HttpResult invoiceB = post("/invoices", singleInvoiceBody(x));
        assertThat(invoiceB.status()).isEqualTo(201);

        // a set another invoice already owns -> 409, unchanged
        HttpResult owned = patch("/invoices/" + invoiceA.id(), singleInvoiceBody(x));
        assertThat(owned.status()).isEqualTo(409);
        assertThat(owned.error()).isEqualTo("invoice_exists_for_set");
        assertThat(owned.body().get("existing_invoice_id").asText()).isEqualTo(invoiceB.id());
        assertUnchanged(invoiceA.id(), trip.size(), "685.70", originalHash);

        // mixed currency -> 409, unchanged
        List<String> mixed = new ArrayList<>(trip);
        mixed.add(usd);
        HttpResult mixedResult = patch("/invoices/" + invoiceA.id(), multiInvoiceBody(mixed));
        assertThat(mixedResult.status()).isEqualTo(409);
        assertThat(mixedResult.error()).isEqualTo("mixed_currency");
        assertUnchanged(invoiceA.id(), trip.size(), "685.70", originalHash);

        // missing id -> 404, unchanged
        HttpResult missing = patch("/invoices/" + invoiceA.id(), singleInvoiceBody("txn_nope"));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.error()).isEqualTo("transaction_not_found");
        assertUnchanged(invoiceA.id(), trip.size(), "685.70", originalHash);

        // valid new set -> full rebuild of items, rollups, total and hash
        HttpResult rebuilt = patch("/invoices/" + invoiceA.id(), multiInvoiceBody(List.of(x, y)));
        assertThat(rebuilt.status()).isEqualTo(200);

        var invoice = get("/invoices/" + invoiceA.id()).body();
        assertThat(invoice.get("item_count").asInt()).isEqualTo(2);
        assertThat(invoice.get("items")).hasSize(2);
        assertThat(invoice.get("txn_set_hash").asText()).isNotEqualTo(originalHash);
        var xBody = get("/transactions/" + x).body();
        var yBody = get("/transactions/" + y).body();
        assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo(
                xBody.get("total").decimalValue().add(yBody.get("total").decimalValue()));
        Set<String> itemIds = new HashSet<>();
        invoice.get("items").forEach(item -> itemIds.add(item.get("transaction_id").asText()));
        assertThat(itemIds).containsExactlyInAnyOrder(x, y);
        // rollups were rebuilt from exactly the new set: flight YQ + rail Booking fee,
        // nothing left over from the original five-txn invoice
        assertThat(invoice.get("fee_rollup")).hasSize(2);
        assertThat(invoice.get("fee_rollup").get(0).get("name").asText()).isEqualTo("Booking fee");
        assertThat(invoice.get("fee_rollup").get(1).get("name").asText()).isEqualTo("YQ");
        assertThat(invoice.get("tax_rollup")).hasSize(1); // only the flight's Airport tax
        assertThat(invoice.get("tax_rollup").get(0).get("name").asText()).isEqualTo("Airport tax");

        // PATCH to the same set again is fine (id <> ? exemption)
        HttpResult again = patch("/invoices/" + invoiceA.id(), multiInvoiceBody(List.of(x, y)));
        assertThat(again.status()).isEqualTo(200);
    }

    // ------------------------------------------------------ plan test 15

    @Test
    void capRepeatedAndMissingIdsAreRejected() {
        // 1001 ids -> 400 too_many_transactions with max echoed
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add("txn_synthetic_" + i);
        }
        HttpResult overCap = post("/invoices", multiInvoiceBody(tooMany));
        assertThat(overCap.status()).isEqualTo(400);
        assertThat(overCap.error()).isEqualTo("too_many_transactions");
        assertThat(overCap.body().get("max").asInt()).isEqualTo(1000);

        // exactly 1000 passes the cap (existence then fails for these synthetic ids)
        List<String> atCap = tooMany.subList(0, 1000);
        assertThat(post("/invoices", multiInvoiceBody(atCap)).status()).isEqualTo(404);

        String existing = post("/transactions", seed("flight")).id();

        // repeated id -> 409 with the repeated ids listed
        HttpResult repeated = post("/invoices", multiInvoiceBody(List.of(existing, existing)));
        assertThat(repeated.status()).isEqualTo(409);
        assertThat(repeated.error()).isEqualTo("duplicate_transaction_id");
        assertThat(repeated.body().get("ids")).isNotNull();

        // missing id -> 404 with the missing ids listed
        HttpResult missing = post("/invoices", multiInvoiceBody(List.of(existing, "txn_missing_1", "txn_missing_2")));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.error()).isEqualTo("transaction_not_found");
        List<String> listed = new ArrayList<>();
        missing.body().get("ids").forEach(node -> listed.add(node.asText()));
        assertThat(listed).containsExactly("txn_missing_1", "txn_missing_2");

        // bad shapes -> 400
        assertThat(post("/invoices", Map.of()).status()).isEqualTo(400);
        assertThat(post("/invoices", Map.of("transaction_ids", List.of())).status()).isEqualTo(400);
        HttpResult both = post("/invoices", Map.of(
                "transaction_id", existing,
                "transaction_ids", List.of(existing)));
        assertThat(both.status()).isEqualTo(400);
        assertThat(invoiceRepository.countAll()).isZero();
    }

    // ------------------------------------------------------------- items

    @Test
    void getInvoicePagesItemsWithCursorAndAlwaysReturnsFullRollups() {
        List<String> trip = createTrip();
        HttpResult created = post("/invoices", multiInvoiceBody(trip));

        // page 1
        var page1 = get("/invoices/" + created.id() + "?items_limit=2").body();
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page1.get("items").get(0).get("position").asInt()).isZero();
        assertThat(page1.get("items_next_cursor").asText()).isEqualTo("1");
        assertThat(page1.get("tax_rollup")).hasSize(2); // full rollups on every page
        assertThat(page1.get("fee_rollup")).hasSize(2);

        // page 2
        var page2 = get("/invoices/" + created.id() + "?items_limit=2&items_cursor=1").body();
        assertThat(page2.get("items")).hasSize(2);
        assertThat(page2.get("items").get(0).get("position").asInt()).isEqualTo(2);
        assertThat(page2.get("items_next_cursor").asText()).isEqualTo("3");

        // last page
        var page3 = get("/invoices/" + created.id() + "?items_limit=2&items_cursor=3").body();
        assertThat(page3.get("items")).hasSize(1);
        assertThat(page3.get("items").get(0).get("position").asInt()).isEqualTo(4);
        assertThat(page3.get("items_next_cursor").isNull()).isTrue();

        // full read by default
        var all = get("/invoices/" + created.id()).body();
        assertThat(all.get("items")).hasSize(5);

        // bounds and invalid cursors are 400
        assertThat(get("/invoices/" + created.id() + "?items_limit=0").status()).isEqualTo(400);
        assertThat(get("/invoices/" + created.id() + "?items_limit=501").status()).isEqualTo(400);
        assertThat(get("/invoices/" + created.id() + "?items_cursor=garbage").status()).isEqualTo(400);
        assertThat(get("/invoices/inv_missing").status()).isEqualTo(404);
        assertThat(delete("/invoices/inv_missing").status()).isEqualTo(404);
    }

    @Test
    void invoiceListReturnsHeadersOnlyWithKeysetPagination() {
        List<String> trip = createTrip();
        // three invoices over three distinct sets
        assertThat(post("/invoices", multiInvoiceBody(List.of(trip.get(0)))).status()).isEqualTo(201);
        assertThat(post("/invoices", multiInvoiceBody(List.of(trip.get(1)))).status()).isEqualTo(201);
        assertThat(post("/invoices", multiInvoiceBody(List.of(trip.get(2), trip.get(3)))).status()).isEqualTo(201);

        var page1 = get("/invoices?limit=2").body();
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page1.get("next_cursor").isNull()).isFalse();

        String cursor = page1.get("next_cursor").asText();
        var page2 = get("/invoices?limit=2&cursor=" + cursor).body();
        assertThat(page2.get("items")).hasSize(1);
        assertThat(page2.get("next_cursor").isNull()).isTrue();

        // headers only: no items or rollups embedded in the list payload
        var header = page1.get("items").get(0);
        assertThat(header.get("item_count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(header.has("items")).isFalse();
        assertThat(header.has("tax_rollup")).isFalse();
        assertThat(header.get("txn_set_hash").asText()).hasSize(64);

        assertThat(get("/invoices?limit=0").status()).isEqualTo(400);
        assertThat(get("/invoices?limit=101").status()).isEqualTo(400);
        assertThat(get("/invoices?cursor=garbage").status()).isEqualTo(400);
    }

    // ------------------------------------------------------------ helpers

    private Map<String, Object> usdSeed() {
        Map<String, Object> body = uniqueSeed("hotel");
        body.put("currency", "USD");
        return body;
    }

    private void assertUnchanged(String invoiceId, int expectedItems, String expectedTotal, String expectedHash) {
        var invoice = get("/invoices/" + invoiceId).body();
        assertThat(invoice.get("item_count").asInt()).isEqualTo(expectedItems);
        assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo(expectedTotal);
        assertThat(invoice.get("txn_set_hash").asText()).isEqualTo(expectedHash);
    }

    private static JsonNode itemByType(JsonNode items, String type) {
        for (JsonNode item : items) {
            if (type.equals(item.get("type").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item of type " + type);
    }

    private static JsonNode findByName(JsonNode array, String name) {
        for (JsonNode element : array) {
            if (name.equals(element.get("name").asText())) {
                return element;
            }
        }
        throw new AssertionError("no entry named " + name);
    }

    private static List<String> listOfStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
