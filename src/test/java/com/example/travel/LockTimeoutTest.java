package com.example.travel;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;

import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan test 19: a lock wait must never surface as 500. This class runs against
 * its own database with a deliberately short LOCK_TIMEOUT (500 ms) so the test
 * is fast; a second raw H2 connection takes a row lock, and the HTTP PATCH that
 * needs the same row has to answer 503 busy with Retry-After: 1.
 */
class LockTimeoutTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("lock-timeout", 500);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @Test
    void contendingPatchAnswers503BusyWithRetryAfterAndAppliesNothing() throws Exception {
        String transactionId = post("/transactions", seed("flight")).id();

        Connection blocker = DriverManager.getConnection(DB_URL, "sa", "");
        try {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement =
                         blocker.prepareStatement("SELECT id FROM transactions WHERE id = ? FOR UPDATE")) {
                statement.setString(1, transactionId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("the blocker must own the row lock").isTrue();
                }
            }

            // the app now waits 500 ms for the same row and gives up
            long start = System.nanoTime();
            HttpResult result = patch("/transactions/" + transactionId, patchedTotal());
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(result.status())
                    .as("lock wait must be 503, never 500 (body: %s)", result.body())
                    .isEqualTo(503);
            assertThat(result.error()).isEqualTo("busy");
            assertThat(retryAfter(result)).isEqualTo("1");
            assertThat(elapsedMillis)
                    .as("must fail fast on the 500 ms lock timeout, not hang or wait for the HTTP timeout")
                    .isLessThan(10_000);

            // nothing from the refused PATCH was applied
            var unchanged = get("/transactions/" + transactionId).body();
            assertThat(unchanged.get("total").decimalValue()).isEqualByComparingTo("220.00");
        } finally {
            blocker.rollback();
            blocker.close();
        }

        // once the lock is gone the very same PATCH succeeds, proving the 503
        // was purely contention and the row was left untouched
        HttpResult retried = patch("/transactions/" + transactionId, patchedTotal());
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("total").decimalValue()).isEqualByComparingTo("999.99");

        assertThat(get("/transactions/" + transactionId).body().get("total").decimalValue())
                .isEqualByComparingTo("999.99");
    }

    // ------------------------------------------------------------ helpers

    /** The published flight seed with only the mutable total changed. */
    private Map<String, Object> patchedTotal() {
        Map<String, Object> body = seed("flight");
        body.put("total", 999.99);
        return body;
    }

    private static String retryAfter(HttpResult result) {
        String value = result.header("Retry-After");
        return value != null ? value : result.header("retry-after");
    }
}
