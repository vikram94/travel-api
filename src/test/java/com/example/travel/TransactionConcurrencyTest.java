package com.example.travel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan test 6: concurrent POSTs with the same external_id must yield exactly
 * one stored transaction — the uniqueness decision belongs to the database
 * constraint, not to in-memory deduplication.
 */
class TransactionConcurrencyTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("txn-concurrency");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @Test
    void eightParallelPostsWithTheSameExternalIdCreateExactlyOneRow() throws Exception {
        int threads = 8;
        List<HttpResult> results = new CopyOnWriteArrayList<>();
        CyclicBarrier startLine = new CyclicBarrier(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    startLine.await();
                    results.add(post("/transactions", seed("flight")));
                    return null;
                });
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).hasSize(threads);
        List<HttpResult> created = results.stream().filter(r -> r.status() == 201).toList();
        List<HttpResult> conflicts = results.stream().filter(r -> r.status() == 409).toList();

        assertThat(created).hasSize(1);
        assertThat(conflicts).hasSize(threads - 1);

        String winnerId = created.getFirst().id();
        conflicts.forEach(conflict -> {
            assertThat(conflict.error()).isEqualTo("duplicate_external_id");
            assertThat(conflict.body().get("existing_id").asString()).isEqualTo(winnerId);
        });

        assertThat(transactionRepository.countAll()).isEqualTo(1);
        assertThat(get("/transactions/" + winnerId).status()).isEqualTo(200);
    }
}
