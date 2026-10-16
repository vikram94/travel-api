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
 * Plan test 18 (external_id whitespace/length rules, coupon arrival before
 * departure, hotel checkout before check-in, invalid currency -> 400 with
 * field messages; empty flight coupons -> 201) plus the remaining payload
 * rules: decimals, enum values, line caps, rate bounds, metadata size.
 */
class ValidationTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("validation");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    // ---------------------------------------------------------- external_id

    @Test
    void blankAndPaddedAndOverlongExternalIdsAre400() {
        Map<String, Object> blank = seed("flight");
        blank.put("external_id", "");
        assertFieldError(post("/transactions", blank), "external_id");

        Map<String, Object> padded = seed("flight");
        padded.put("external_id", " flight-TXL ");
        assertFieldError(post("/transactions", padded), "external_id");

        Map<String, Object> newline = seed("flight");
        newline.put("external_id", "flight\nTXL");
        assertFieldError(post("/transactions", newline), "external_id");

        Map<String, Object> overlong = seed("flight");
        overlong.put("external_id", "x".repeat(256));
        assertFieldError(post("/transactions", overlong), "external_id");

        // stored exactly as sent, case-sensitively, when valid
        Map<String, Object> valid = seed("flight");
        valid.put("external_id", "CaseSensitive-ID");
        assertThat(post("/transactions", valid).status()).isEqualTo(201);
        assertThat(transactionRepository.countAll()).isEqualTo(1);
    }

    // ---------------------------------------------------------- timestamps

    @Test
    void missingOrUnparseableTimestampsAre400() {
        Map<String, Object> missing = seed("flight");
        missing.put("occurred_at", null);
        assertFieldError(post("/transactions", missing), "occurred_at");

        Map<String, Object> garbage = seed("flight");
        garbage.put("occurred_at", "yesterday-ish");
        assertFieldError(post("/transactions", garbage), "occurred_at");

        Map<String, Object> noOffset = seed("flight");
        noOffset.put("occurred_at", "2026-03-11T06:40:00");
        assertFieldError(post("/transactions", noOffset), "occurred_at");
    }

    // -------------------------------------------------------- flight rules

    @Test
    void couponArrivalBeforeDepartureIs400() {
        Map<String, Object> body = seed("flight");
        List<Map<String, Object>> coupons = nested(body, "metadata", "coupons");
        coupons.getFirst().put("arrival", "2026-03-11T06:00:00Z");

        HttpResult result = post("/transactions", body);
        assertFieldError(result, "metadata.coupons[0].arrival");
    }

    @Test
    void emptyFlightCouponsAreAccepted() {
        HttpResult result = post("/transactions", seed("duplicate_flight"));
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("metadata").get("coupons")).isEmpty();
    }

    @Test
    void flightWithoutRequiredMetadataFieldsIs400() {
        Map<String, Object> body = seed("flight");
        body.put("metadata", Map.of("destination", "LHR"));
        HttpResult result = post("/transactions", body);
        assertFieldError(result, "metadata.origin");
        assertFieldError(result, "metadata.fare");
        assertFieldError(result, "metadata.coupons");
    }

    // --------------------------------------------------------- hotel rules

    @Test
    void hotelCheckoutBeforeCheckInIs400() {
        Map<String, Object> body = seed("hotel");
        Map<String, Object> metadata = nested(body, "metadata");
        metadata.put("check_out", "2026-03-10");

        assertFieldError(post("/transactions", body), "metadata.check_out");
    }

    @Test
    void hotelNightsMustMatchTheStayDates() {
        Map<String, Object> body = seed("hotel");
        Map<String, Object> metadata = nested(body, "metadata");
        metadata.put("check_out", "2026-03-14"); // 3 calendar days but nights=2

        assertFieldError(post("/transactions", body), "metadata.nights");
    }

    // -------------------------------------------------------- currency/rate

    @Test
    void invalidCurrenciesAre400() {
        for (String currency : List.of("EURO", "eur", "E4R", "EU")) {
            Map<String, Object> body = seed("flight");
            body.put("currency", currency);
            assertFieldError(post("/transactions", body), "currency");
        }
    }

    @Test
    void taxRateMustBeBetweenZeroAndOne() {
        Map<String, Object> tooHigh = seed("flight");
        tooHigh.put("tax_lines", List.of(Map.of("name", "VAT", "rate", 1.5, "amount", 1.0)));
        assertFieldError(post("/transactions", tooHigh), "tax_lines[0].rate");

        Map<String, Object> negative = seed("flight");
        negative.put("tax_lines", List.of(Map.of("name", "VAT", "rate", -0.01, "amount", 1.0)));
        assertFieldError(post("/transactions", negative), "tax_lines[0].rate");
    }

    // ------------------------------------------------------------ amounts

    @Test
    void amountsWithMoreThanTwoDecimalsOrNegativesAre400() {
        Map<String, Object> precise = seed("flight");
        precise.put("total", 220.005);
        assertFieldError(post("/transactions", precise), "total");

        Map<String, Object> negative = seed("flight");
        negative.put("total", -1.0);
        assertFieldError(post("/transactions", negative), "total");

        Map<String, Object> negativeLine = seed("flight");
        negativeLine.put("fee_lines", List.of(Map.of("name", "YQ", "amount", -25.0)));
        assertFieldError(post("/transactions", negativeLine), "fee_lines[0].amount");
    }

    // --------------------------------------------------------- enums/shape

    @Test
    void unknownTypeAndFeeKindAre400() {
        Map<String, Object> type = seed("flight");
        type.put("type", "spaceship");
        assertFieldError(post("/transactions", type), "type");

        Map<String, Object> feeKind = seed("trip_fee");
        Map<String, Object> feeKindMetadata = nested(feeKind, "metadata");
        feeKindMetadata.put("fee_kind", "bribe");
        assertFieldError(post("/transactions", feeKind), "metadata.fee_kind");
    }

    @Test
    void railWithoutLegsIs400() {
        Map<String, Object> body = seed("rail");
        Map<String, Object> metadata = nested(body, "metadata");
        metadata.put("legs", List.of());
        assertFieldError(post("/transactions", body), "metadata.legs");
    }

    @Test
    void missingRequiredTopLevelFieldsAre400WithEveryFieldListed() {
        HttpResult result = post("/transactions", Map.of());
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.error()).isEqualTo("validation_error");
        assertThat(fieldErrors(result.body())).contains(
                "type", "external_id", "currency", "occurred_at", "total",
                "tax_lines", "fee_lines", "metadata");
        assertThat(transactionRepository.countAll()).isZero();
    }

    // -------------------------------------------------------------- limits

    @Test
    void atMostOneHundredTaxAndFeeLines() {
        Map<String, Object> ok = seed("flight");
        ok.put("tax_lines", namedAmounts(100));
        ok.put("fee_lines", namedAmounts(100));
        assertThat(post("/transactions", ok).status()).isEqualTo(201);

        Map<String, Object> tooMany = seed("rail");
        tooMany.put("tax_lines", namedAmounts(101));
        assertFieldError(post("/transactions", tooMany), "tax_lines");
    }

    @Test
    void metadataMustStayUnderSixtyFourKilobytes() {
        Map<String, Object> body = seed("flight");
        Map<String, Object> metadata = nested(body, "metadata");
        metadata.put("padding", "x".repeat(70_000));

        HttpResult result = post("/transactions", body);
        assertFieldError(result, "metadata");
        assertThat(transactionRepository.countAll()).isZero();
    }

    // ------------------------------------------------------------ helpers

    private static void assertFieldError(HttpResult result, String field) {
        assertThat(result.status())
                .as("status for field %s, body: %s", field, result.body())
                .isEqualTo(400);
        assertThat(result.error()).isEqualTo("validation_error");
        assertThat(fieldErrors(result.body()))
                .as("errors for field %s, body: %s", field, result.body())
                .contains(field);
    }

    private static List<String> fieldErrors(JsonNode body) {
        List<String> fields = new java.util.ArrayList<>();
        body.get("errors").forEach(error -> fields.add(error.get("field").asText()));
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static <T> T nested(Object node, String... path) {
        Object current = node;
        for (String segment : path) {
            current = ((Map<String, Object>) current).get(segment);
        }
        return (T) current;
    }

    private static List<Map<String, Object>> namedAmounts(int count) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(Map.of("name", "Line " + i, "amount", 1.0));
        }
        return lines;
    }
}
