package com.example.travel;

import java.util.List;
import java.util.Map;

import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan tests 1 (create all seed types + round-trip), 2 (duplicate external_id),
 * 7 (PATCH on invoiced transaction), 8 (DELETE on invoiced transaction), plus
 * the basic GET/PATCH/DELETE status codes.
 */
class TransactionCrudTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("txn-crud");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @Test
    void healthEndpointReportsOkWithoutTouchingTheDatabase() {
        HttpResult result = get("/health");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("status").asText()).isEqualTo("ok");
    }

    @Test
    void createsFlightHotelRailAndBothNavanFeeKindsFromSeedPayloads() {
        HttpResult flight = post("/transactions", seed("flight"));
        HttpResult hotel = post("/transactions", seed("hotel"));
        HttpResult rail = post("/transactions", seed("rail"));
        HttpResult tripFee = post("/transactions", seed("trip_fee"));
        HttpResult agentFee = post("/transactions", seed("agent_call_fee"));

        assertThat(List.of(flight.status(), hotel.status(), rail.status(), tripFee.status(), agentFee.status()))
                .containsExactly(201, 201, 201, 201, 201);

        var gotFlight = get("/transactions/" + flight.id()).body();
        assertThat(gotFlight.get("type").asText()).isEqualTo("flight");
        assertThat(gotFlight.get("total").decimalValue()).isEqualByComparingTo("220");
        assertThat(gotFlight.get("metadata").get("origin").asText()).isEqualTo("TXL");
        assertThat(gotFlight.get("metadata").get("coupons")).hasSize(1);
        assertThat(gotFlight.get("metadata").get("coupons").get(0).get("flight_number").asText())
                .isEqualTo("BA982");
        assertThat(gotFlight.get("tax_lines").get(0).get("name").asText()).isEqualTo("Airport tax");
        assertThat(gotFlight.get("tax_lines").get(0).get("amount").decimalValue()).isEqualByComparingTo("15");
        assertThat(gotFlight.get("fee_lines").get(0).get("name").asText()).isEqualTo("YQ");

        var gotHotel = get("/transactions/" + hotel.id()).body();
        assertThat(gotHotel.get("type").asText()).isEqualTo("hotel");
        assertThat(gotHotel.get("metadata").get("name").asText()).isEqualTo("Hotel Mitte London");
        assertThat(gotHotel.get("metadata").get("address").get("city").asText()).isEqualTo("London");
        assertThat(gotHotel.get("metadata").get("nights").asInt()).isEqualTo(2);
        assertThat(gotHotel.get("tax_lines").get(0).get("rate").decimalValue()).isEqualByComparingTo("0.07");

        var gotRail = get("/transactions/" + rail.id()).body();
        assertThat(gotRail.get("type").asText()).isEqualTo("rail");
        assertThat(gotRail.get("metadata").get("legs")).hasSize(2);
        assertThat(gotRail.get("metadata").get("legs").get(1).get("train_number").asText()).isEqualTo("EST9122");

        assertThat(get("/transactions/" + tripFee.id()).body().get("metadata").get("fee_kind").asText())
                .isEqualTo("trip_fee");
        assertThat(get("/transactions/" + agentFee.id()).body().get("metadata").get("fee_kind").asText())
                .isEqualTo("agent_call_fee");

        assertThat(transactionRepository.countAll()).isEqualTo(5);
    }

    @Test
    void duplicateExternalIdReturns409WithExistingIdAndOneRow() {
        HttpResult first = post("/transactions", seed("flight"));
        assertThat(first.status()).isEqualTo(201);

        HttpResult second = post("/transactions", seed("flight"));
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.error()).isEqualTo("duplicate_external_id");
        assertThat(second.body().get("existing_id").asText()).isEqualTo(first.id());

        assertThat(transactionRepository.countAll()).isEqualTo(1);
    }

    @Test
    void duplicateExternalIdConflictsEvenWhenTheBodyDiffers() {
        HttpResult first = post("/transactions", seed("flight"));
        Map<String, Object> different = seed("flight");
        different.put("total", 999.99);

        HttpResult second = post("/transactions", different);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().get("existing_id").asText()).isEqualTo(first.id());
        assertThat(transactionRepository.countAll()).isEqualTo(1);
        // the loser must not rewrite the winner
        assertThat(get("/transactions/" + first.id()).body().get("total").decimalValue())
                .isEqualByComparingTo("220");
    }

    @Test
    void getMissingTransactionIs404() {
        HttpResult result = get("/transactions/txn_does_not_exist");
        assertThat(result.status()).isEqualTo(404);
        assertThat(result.error()).isEqualTo("not_found");
    }

    @Test
    void patchReplacesWholeContentAndKeepsImmutableFields() {
        String id = post("/transactions", seed("flight")).id();

        Map<String, Object> body = seed("flight");
        body.put("total", 250.55);
        body.put("occurred_at", "2026-03-12T10:00:00Z");
        body.put("tax_lines", List.of(Map.of("name", "Security fee", "amount", 10.55)));
        body.put("fee_lines", List.of());

        HttpResult result = patch("/transactions/" + id, body);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("total").decimalValue()).isEqualByComparingTo("250.55");
        assertThat(result.body().get("tax_lines")).hasSize(1);
        assertThat(result.body().get("tax_lines").get(0).get("name").asText()).isEqualTo("Security fee");
        assertThat(result.body().get("fee_lines")).isEmpty();
        assertThat(result.body().get("external_id").asText()).isEqualTo(body.get("external_id"));

        var got = get("/transactions/" + id).body();
        assertThat(got.get("total").decimalValue()).isEqualByComparingTo("250.55");
        assertThat(got.get("occurred_at").asText()).isEqualTo("2026-03-12T10:00:00Z");
    }

    @Test
    void patchChangingImmutableFieldsIs400() {
        String id = post("/transactions", seed("flight")).id();

        Map<String, Object> wrongType = seed("flight");
        wrongType.put("type", "hotel");
        assertThat(patch("/transactions/" + id, wrongType).status()).isEqualTo(400);

        Map<String, Object> wrongCurrency = seed("flight");
        wrongCurrency.put("currency", "USD");
        HttpResult result = patch("/transactions/" + id, wrongCurrency);
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.error()).isEqualTo("validation_error");

        // nothing persisted by the rejected patches
        var got = get("/transactions/" + id).body();
        assertThat(got.get("type").asText()).isEqualTo("flight");
        assertThat(got.get("currency").asText()).isEqualTo("EUR");
    }

    @Test
    void patchMissingTransactionIs404() {
        assertThat(patch("/transactions/txn_missing", seed("flight")).status()).isEqualTo(404);
    }

    @Test
    void deleteRemovesTheTransactionAndRepeatDeleteIs404() {
        String id = post("/transactions", seed("flight")).id();

        assertThat(delete("/transactions/" + id).status()).isEqualTo(204);
        assertThat(get("/transactions/" + id).status()).isEqualTo(404);
        assertThat(delete("/transactions/" + id).status()).isEqualTo(404);
        assertThat(transactionRepository.countAll()).isZero();
    }

    @Test
    void patchAndDeleteOfInvoicedTransactionAre409AndPersistNothing() {
        String flightId = post("/transactions", seed("flight")).id();
        HttpResult invoice = post("/invoices", singleInvoiceBody(flightId));
        assertThat(invoice.status()).isEqualTo(201);

        // plan test 7: PATCH refused, invoice snapshot untouched
        Map<String, Object> patchBody = seed("flight");
        patchBody.put("total", 999.99);
        HttpResult patched = patch("/transactions/" + flightId, patchBody);
        assertThat(patched.status()).isEqualTo(409);
        assertThat(patched.error()).isEqualTo("transaction_invoiced");
        assertThat(get("/transactions/" + flightId).body().get("total").decimalValue())
                .isEqualByComparingTo("220");
        assertThat(get("/invoices/" + invoice.id()).body().get("total").decimalValue())
                .isEqualByComparingTo("220");

        // plan test 8: DELETE refused, row still exists
        HttpResult deleted = delete("/transactions/" + flightId);
        assertThat(deleted.status()).isEqualTo(409);
        assertThat(deleted.error()).isEqualTo("transaction_invoiced");
        assertThat(get("/transactions/" + flightId).status()).isEqualTo(200);
        assertThat(transactionRepository.countAll()).isEqualTo(1);
    }
}
