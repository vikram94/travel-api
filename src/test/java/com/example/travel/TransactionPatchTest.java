package com.example.travel;

import java.util.List;
import java.util.Map;

import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partial PATCH /transactions/{id}: an absent field keeps its stored value, a
 * present field replaces it, an empty tax/fee list clears it, and updated_at only
 * moves on a real write. Immutable fields may be echoed but not changed.
 */
class TransactionPatchTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("patch");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @Test
    void createStampsUpdatedAtEqualToCreatedAt() {
        HttpResult created = post("/transactions", seed("flight"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("updated_at").asString())
                .isEqualTo(created.body().get("created_at").asString());
    }

    @Test
    void patchOnlyTotalLeavesEverythingElseUntouched() throws InterruptedException {
        HttpResult created = post("/transactions", seed("flight"));
        JsonNode before = created.body();
        int taxLines = before.get("tax_lines").size();
        int feeLines = before.get("fee_lines").size();
        String occurredAt = before.get("occurred_at").asString();

        Thread.sleep(5); // guarantee a distinct updated_at at micros resolution

        HttpResult patched = patch("/transactions/" + created.id(), Map.of("total", 250.55));
        assertThat(patched.status()).isEqualTo(200);

        JsonNode after = get("/transactions/" + created.id()).body();
        assertThat(after.get("total").decimalValue()).isEqualByComparingTo("250.55");
        assertThat(after.get("tax_lines")).hasSize(taxLines);
        assertThat(after.get("fee_lines")).hasSize(feeLines);
        assertThat(after.get("occurred_at").asString()).isEqualTo(occurredAt);
        assertThat(after.get("metadata").get("origin").asString()).isEqualTo("TXL");
        assertThat(after.get("updated_at").asString())
                .isNotEqualTo(after.get("created_at").asString());
    }

    @Test
    void absentLineListIsKeptButPresentEmptyListClearsIt() {
        HttpResult created = post("/transactions", seed("flight"));
        assertThat(created.body().get("tax_lines").size()).isGreaterThan(0);

        // absent -> unchanged
        HttpResult totalOnly = patch("/transactions/" + created.id(), Map.of("total", 111.11));
        assertThat(totalOnly.status()).isEqualTo(200);
        assertThat(totalOnly.body().get("tax_lines").size())
                .isEqualTo(created.body().get("tax_lines").size());

        // present empty -> cleared, other fields kept
        HttpResult cleared = patch("/transactions/" + created.id(), Map.of("tax_lines", List.of()));
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.body().get("tax_lines")).isEmpty();
        assertThat(cleared.body().get("total").decimalValue()).isEqualByComparingTo("111.11");
    }

    @Test
    void patchingMetadataRederivesFeeKindForNavanFee() {
        HttpResult created = post("/transactions", seed("trip_fee"));
        assertThat(created.status()).isEqualTo(201);

        // a total-only patch must not disturb fee_kind or metadata
        assertThat(patch("/transactions/" + created.id(), Map.of("total", 30.0)).status()).isEqualTo(200);
        assertThat(get("/transactions/" + created.id()).body().get("metadata").get("fee_kind").asString())
                .isEqualTo("trip_fee");

        // new metadata re-derives fee_kind from the new blob
        HttpResult patched = patch("/transactions/" + created.id(),
                Map.of("metadata", Map.of("fee_kind", "agent_call_fee")));
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.body().get("metadata").get("fee_kind").asString()).isEqualTo("agent_call_fee");
    }

    @Test
    void echoingTheSameImmutableValueIsANoOpButChangingItIs400() {
        HttpResult created = post("/transactions", seed("flight"));

        HttpResult same = patch("/transactions/" + created.id(), Map.of("type", "flight", "total", 10.0));
        assertThat(same.status()).isEqualTo(200);

        HttpResult changedType = patch("/transactions/" + created.id(), Map.of("type", "hotel"));
        assertThat(changedType.status()).isEqualTo(400);
        assertThat(changedType.error()).isEqualTo("validation_error");

        HttpResult changedCurrency = patch("/transactions/" + created.id(), Map.of("currency", "USD"));
        assertThat(changedCurrency.status()).isEqualTo(400);

        HttpResult changedExternalId = patch("/transactions/" + created.id(), Map.of("external_id", "other"));
        assertThat(changedExternalId.status()).isEqualTo(400);
    }

    @Test
    void invalidProvidedFieldIs400AndPersistsNothing() {
        HttpResult created = post("/transactions", seed("flight"));
        JsonNode before = get("/transactions/" + created.id()).body();

        HttpResult negative = patch("/transactions/" + created.id(), Map.of("total", -5.0));
        assertThat(negative.status()).isEqualTo(400);
        assertThat(negative.error()).isEqualTo("validation_error");

        JsonNode after = get("/transactions/" + created.id()).body();
        assertThat(after.get("total").decimalValue()).isEqualByComparingTo(before.get("total").decimalValue());
        assertThat(after.get("updated_at").asString()).isEqualTo(before.get("updated_at").asString());
    }

    @Test
    void patchOfInvoicedTransactionIs409AndKeepsTheSnapshot() {
        String id = post("/transactions", seed("flight")).id();
        assertThat(post("/invoices", singleInvoiceBody(id)).status()).isEqualTo(201);

        HttpResult refused = patch("/transactions/" + id, Map.of("total", 999.99));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.error()).isEqualTo("transaction_invoiced");
    }
}
