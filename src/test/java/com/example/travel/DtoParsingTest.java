package com.example.travel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTO/parsing hardening from the code review: hostile decimal exponents must be a
 * 400 (never a 500), malformed JSON and wrong JSON types must still produce our
 * ApiError, and rates are echoed without trailing zeros.
 */
class DtoParsingTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("dto-parsing");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    // ------------------------------------------------------- decimal bounds

    @Test
    void hostileDecimalExponentsAre400Not500() {
        // These previously blew up in movePointRight -> ArithmeticException -> 500.
        for (String literal : new String[]{"1E+999999999", "1E+2147483647", "1E-999999999", "-1E+999999999"}) {
            HttpResult result = postRaw("/transactions", flightBody(
                    "\"total\":" + literal + ",\"tax_lines\":[],\"fee_lines\":[]"));
            assertThat(result.status())
                    .as("status for total=%s, body=%s", literal, result.body())
                    .isEqualTo(400);
            assertThat(result.error()).isEqualTo("validation_error");
            assertThat(fieldErrors(result.body())).contains("total");
        }
    }

    @Test
    void hostileDecimalExponentInFeeLineIs400Not500() {
        HttpResult result = postRaw("/transactions", flightBody(
                "\"total\":220.0,\"tax_lines\":[],\"fee_lines\":[{\"name\":\"YQ\",\"amount\":1E+999999999}]"));
        assertThat(result.status()).isEqualTo(400);
        assertThat(fieldErrors(result.body())).contains("fee_lines[0].amount");
    }

    @Test
    void hostileRateExponentNeverServerErrors() {
        // rate above 1 -> rejected by the range check before any scaling
        HttpResult tooBig = postRaw("/transactions", flightBody(
                "\"total\":220.0,\"tax_lines\":[{\"name\":\"VAT\",\"rate\":1E+999999999,\"amount\":1.0}],\"fee_lines\":[]"));
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(fieldErrors(tooBig.body())).contains("tax_lines[0].rate");

        // a vanishingly small but in-range rate must not blow up setScale either
        HttpResult tiny = postRaw("/transactions", flightBody(
                "\"total\":220.0,\"tax_lines\":[{\"name\":\"VAT\",\"rate\":1E-999999999,\"amount\":1.0}],\"fee_lines\":[]"));
        assertThat(tiny.status()).isNotEqualTo(500);
    }

    // ----------------------------------------------- malformed / wrong types

    @Test
    void malformedBodyReturnsOurApiError() {
        HttpResult result = postRaw("/transactions", "{");
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().get("error").asString()).isEqualTo("invalid_json");
    }

    @Test
    void wrongJsonTypesReturnOurApiError() {
        HttpResult amount = post("/transactions", withField("total", "abc"));
        assertThat(amount.status()).isEqualTo(400);
        assertThat(amount.body().get("error").asString()).isEqualTo("invalid_json");

        HttpResult list = post("/transactions", withField("tax_lines", "x"));
        assertThat(list.status()).isEqualTo(400);
        assertThat(list.body().get("error").asString()).isEqualTo("invalid_json");
    }

    @Test
    void nullListElementsAre400() {
        HttpResult tax = postRaw("/transactions", flightBody(
                "\"total\":220.0,\"tax_lines\":[null],\"fee_lines\":[]"));
        assertThat(tax.status()).isEqualTo(400);

        HttpResult fee = postRaw("/transactions", flightBody(
                "\"total\":220.0,\"tax_lines\":[],\"fee_lines\":[null]"));
        assertThat(fee.status()).isEqualTo(400);

        assertThat(transactionRepository.countAll()).isZero();
    }

    // ------------------------------------------------------- rate rendering

    @Test
    void offsetTimestampsAreNormalizedToUtc() {
        HttpResult created = post("/transactions", withField("occurred_at", "2026-03-11T07:40:00+01:00"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("occurred_at").asString()).isEqualTo("2026-03-11T06:40:00Z");
    }

    @Test
    void ratesAreEchoedWithoutTrailingZeros() {
        // 0.07 persisted as DECIMAL(7,4) must come back as 0.07, not 0.0700
        HttpResult hotel = post("/transactions", seed("hotel"));
        assertThat(hotel.status()).isEqualTo(201);
        assertThat(hotel.body().get("tax_lines").get(0).get("rate").asString()).isEqualTo("0.07");

        // a whole rate must come back as 1, not 1E+0
        HttpResult flight = post("/transactions", withField("tax_lines",
                List.of(Map.of("name", "VAT", "rate", 1, "amount", 1.0))));
        assertThat(flight.status()).isEqualTo(201);
        assertThat(flight.body().get("tax_lines").get(0).get("rate").asString()).isEqualTo("1");

        // and the invoice rollup echoes the normalized rate too
        HttpResult invoice = post("/invoices", singleInvoiceBody(hotel.id()));
        assertThat(invoice.status()).isEqualTo(201);
        assertThat(invoice.body().has("txn_set_hash")).isFalse();
        assertThat(invoice.body().get("tax_rollup").get(0).get("rate").asString()).isEqualTo("0.07");
    }

    // ------------------------------------------------------------ helpers

    private Map<String, Object> withField(String field, Object value) {
        Map<String, Object> body = seed("flight");
        body.put("external_id", "raw-" + UUID.randomUUID());
        body.put(field, value);
        return body;
    }

    /** A valid flight body as raw text; the fragment supplies total + line lists. */
    private static String flightBody(String fieldsAfterOccurredAt) {
        return """
                {"type":"flight","external_id":"raw-%s","currency":"EUR",
                 "occurred_at":"2026-03-11T06:40:00Z",%s,
                 "metadata":{"origin":"TXL","destination":"LHR","fare":1.0,"coupons":[]}}
                """.formatted(UUID.randomUUID(), fieldsAfterOccurredAt);
    }

    private static List<String> fieldErrors(JsonNode body) {
        List<String> fields = new ArrayList<>();
        body.get("errors").forEach(error -> fields.add(error.get("field").asString()));
        return fields;
    }
}
