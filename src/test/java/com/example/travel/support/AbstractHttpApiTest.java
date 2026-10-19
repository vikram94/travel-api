package com.example.travel.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.travel.invoice.InvoiceRepository;
import com.example.travel.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for real-HTTP tests: random port, file-backed H2, and the actual
 * application (no mocks, no in-memory database). Each concrete class points at
 * its own database via its own @DynamicPropertySource; tables are cleared
 * before each test. Requests go through the JDK HttpClient so assertions see
 * exactly what a client would see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractHttpApiTest {

    protected static final JsonNode SEEDS = loadSeeds();
    protected static final JsonNode EXPECTED = loadExpected();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected NamedParameterJdbcTemplate jdbc;

    @Autowired
    protected TransactionRepository transactionRepository;

    @Autowired
    protected InvoiceRepository invoiceRepository;

    @Autowired
    private Environment environment;

    protected int port;

    @BeforeEach
    void initHttp() {
        port = environment.getProperty("local.server.port", Integer.class);
        cleanTables();
    }

    private void cleanTables() {
        for (String table : List.of("invoice_items", "invoice_tax_rollup", "invoice_fee_rollup", "invoices",
                "transaction_tax_lines", "transaction_fee_lines", "transactions")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
    }

    // ----------------------------------------------------------- HTTP verbs

    protected record HttpResult(int status, JsonNode body, Map<String, List<String>> headers) {

        public String id() {
            return body().get("id").asString();
        }

        public String error() {
            return body().get("error").asString();
        }

        public String header(String name) {
            List<String> values = headers().get(name);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    protected HttpResult post(String path, Object body) {
        return exchange("POST", path, body);
    }

    /** Raw body for cases that must not be pre-encoded (malformed JSON / literal numbers). */
    protected HttpResult postRaw(String path, String rawJson) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rawJson))
                .build());
    }

    protected HttpResult get(String path) {
        return exchange("GET", path, null);
    }

    protected HttpResult patch(String path, Object body) {
        return exchange("PATCH", path, body);
    }

    protected HttpResult delete(String path) {
        return exchange("DELETE", path, null);
    }

    private HttpResult exchange(String method, String path, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        }
        return send(builder.build());
    }

    private HttpResult send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String payload = response.body();
            JsonNode json = payload == null || payload.isBlank() ? null : objectMapper.readTree(payload);
            return new HttpResult(response.statusCode(), json, response.headers().map());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during HTTP call", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HTTP call failed: " + request.method() + " " + request.uri(), e);
        }
    }

    // ------------------------------------------------------------ fixtures

    /** Deep copy of a seed payload exactly as published in the fixtures file. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> seed(String key) {
        return objectMapper.convertValue(SEEDS.get(key), Map.class);
    }

    /** Seed payload with a fresh external_id (for tests that create several). */
    protected Map<String, Object> uniqueSeed(String key) {
        Map<String, Object> body = seed(key);
        body.put("external_id", body.get("external_id") + "-" + java.util.UUID.randomUUID());
        return body;
    }

    /** Posts the five trip seeds (flight, hotel, rail, trip fee, agent call fee). */
    protected List<String> createTrip() {
        return List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee").stream()
                .map(key -> post("/transactions", seed(key)).id())
                .toList();
    }

    protected static Map<String, Object> singleInvoiceBody(String transactionId) {
        return Map.of("transaction_id", transactionId);
    }

    protected static Map<String, Object> multiInvoiceBody(List<String> transactionIds) {
        return Map.of("transaction_ids", transactionIds);
    }

    private static JsonNode loadSeeds() {
        try {
            return new ObjectMapper().readTree(
                    Files.readString(Path.of("fixtures/task-b/seed-payloads.json")));
        } catch (Exception e) {
            throw new IllegalStateException("cannot load fixtures/task-b/seed-payloads.json", e);
        }
    }

    private static JsonNode loadExpected() {
        try {
            return new ObjectMapper().readTree(
                    Files.readString(Path.of("fixtures/task-b/expected-invoice.json")));
        } catch (Exception e) {
            throw new IllegalStateException("cannot load fixtures/task-b/expected-invoice.json", e);
        }
    }
}
