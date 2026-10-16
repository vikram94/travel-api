package com.example.travel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan tests 12 (concurrent identical invoice creation has one winner, losers
 * get 409 with the winner's id) and 16 (invoice creation racing a PATCH:
 * exactly one ordering, final state consistent either way).
 */
class InvoiceConcurrencyTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("invoice-concurrency");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    // ------------------------------------------------------ plan test 12

    @Test
    void sixParallelIdenticalInvoiceCreatesProduceExactlyOneInvoice() throws Exception {
        List<String> trip = createTrip();

        List<HttpResult> results = runConcurrently(6,
                () -> post("/invoices", multiInvoiceBody(trip)));
        List<HttpResult> created = results.stream().filter(r -> r.status() == 201).toList();
        List<HttpResult> conflicts = results.stream().filter(r -> r.status() == 409).toList();

        assertThat(created).hasSize(1);
        assertThat(conflicts).hasSize(5);

        String winnerId = created.getFirst().id();
        conflicts.forEach(conflict -> {
            assertThat(conflict.error()).isEqualTo("invoice_exists_for_set");
            assertThat(conflict.body().get("existing_invoice_id").asText()).isEqualTo(winnerId);
        });

        assertThat(invoiceRepository.countAll()).isEqualTo(1);
        var invoice = get("/invoices/" + winnerId).body();
        assertThat(invoice.get("item_count").asInt()).isEqualTo(5);
        assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo("685.70");
    }

    // ------------------------------------------------------ plan test 16

    @Test
    void invoiceCreateRacingFlightPatchEndsConsistentWhicheverWins() throws Exception {
        String flightId = post("/transactions", seed("flight")).id();

        Map<String, HttpResult> results = new java.util.concurrent.ConcurrentHashMap<>();
        runConcurrently(
                () -> {
                    results.put("patch", patch("/transactions/" + flightId, flightPatch(999.99)));
                    return null;
                },
                () -> {
                    results.put("invoice", post("/invoices", multiInvoiceBody(List.of(flightId))));
                    return null;
                });

        HttpResult patchResult = results.get("patch");
        HttpResult invoiceResult = results.get("invoice");

        // the invoice is always created; the PATCH either beat it (200) or was
        // correctly refused because the transaction became frozen (409)
        assertThat(invoiceResult.status()).isEqualTo(201);
        assertThat(patchResult.status()).isIn(200, 409);

        var transaction = get("/transactions/" + flightId).body();
        var invoice = get("/invoices/" + invoiceResult.id()).body();

        // invariant: the snapshot always equals the stored transaction in the
        // final state — the race could never interleave them
        BigDecimal transactionTotal = transaction.get("total").decimalValue();
        var invoiceFlightItem = invoice.get("items").get(0);
        assertThat(invoiceFlightItem.get("transaction_id").asText()).isEqualTo(flightId);
        assertThat(invoiceFlightItem.get("total").decimalValue())
                .isEqualByComparingTo(transactionTotal);
        assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo(transactionTotal);

        if (patchResult.status() == 409) {
            assertThat(transactionTotal).isEqualByComparingTo("220.00");
        } else {
            assertThat(transactionTotal).isEqualByComparingTo("999.99");
        }
    }

    // ------------------------------------------------------------ helpers

    /** Seed flight body with only the total changed. */
    private java.util.Map<String, Object> flightPatch(double total) {
        java.util.Map<String, Object> body = seed("flight");
        body.put("total", total);
        return body;
    }

    /** Runs each given action in parallel — one thread per action. */
    @SafeVarargs
    private List<HttpResult> runConcurrently(Supplier<HttpResult>... actions) throws Exception {
        return runAllConcurrently(List.of(actions));
    }

    /** Runs the same action {@code times} times in parallel. */
    private List<HttpResult> runConcurrently(int times, Supplier<HttpResult> action) throws Exception {
        List<Supplier<HttpResult>> actions = new java.util.ArrayList<>();
        for (int i = 0; i < times; i++) {
            actions.add(action);
        }
        return runAllConcurrently(actions);
    }

    private List<HttpResult> runAllConcurrently(List<Supplier<HttpResult>> actions) throws Exception {
        int threads = actions.size();
        List<HttpResult> results = new CopyOnWriteArrayList<>();
        CyclicBarrier startLine = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (Supplier<HttpResult> action : actions) {
                futures.add(pool.submit(() -> {
                    startLine.await();
                    results.add(action.get());
                    return null;
                }));
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
                    .as("concurrent requests did not finish in time")
                    .isTrue();
        }
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(); // rethrow any worker failure with its stack trace
        }
        assertThat(results).hasSize(threads);
        return results;
    }
}
