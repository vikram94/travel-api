package com.example.travel;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.travel.domain.FeeLine;
import com.example.travel.domain.TaxLine;
import com.example.travel.domain.TransactionType;
import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import com.example.travel.transaction.StoredTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan tests 10 (keyset pagination: default page, cursor continuation, no
 * duplicates or gaps, null cursor at the end) and 17 (bounded limits and
 * invalid-cursor handling). Rows are bulk-inserted through the repository so
 * seeding stays fast while still exercising the real schema.
 */
class TransactionListTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("txn-list");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    // ------------------------------------------------------ plan test 10

    @Test
    void defaultPageIs50AndTheCursorWalksWithoutDuplicatesOrGaps() {
        seedTransactions(60);

        var page1 = get("/transactions").body();
        assertThat(page1.get("items")).hasSize(50);
        assertThat(page1.get("next_cursor").isNull()).isFalse();

        String cursor = page1.get("next_cursor").asText();
        var page2 = get("/transactions?cursor=" + cursor).body();
        assertThat(page2.get("items")).hasSize(10);
        assertThat(page2.get("next_cursor").isNull()).isTrue();

        // union of both pages is exactly the seeded set: no repeats, no gaps
        Set<String> seenIds = new HashSet<>();
        Set<String> seenExternalIds = new HashSet<>();
        page1.get("items").forEach(item -> {
            seenIds.add(item.get("id").asText());
            seenExternalIds.add(item.get("external_id").asText());
        });
        page2.get("items").forEach(item -> {
            seenIds.add(item.get("id").asText());
            seenExternalIds.add(item.get("external_id").asText());
        });
        assertThat(seenIds).hasSize(60);
        assertThat(seenExternalIds).hasSize(60);

        // created_at is strictly increasing across the boundary
        var lastOfPage1 = page1.get("items").get(49);
        var firstOfPage2 = page2.get("items").get(0);
        assertThat(firstOfPage2.get("created_at").asText())
                .isGreaterThan(lastOfPage1.get("created_at").asText());
    }

    @Test
    void childLinesAreHydratedPerPageNotPerRow() {
        seedTransactions(10);

        var page = get("/transactions?limit=100").body();
        assertThat(page.get("items")).hasSize(10);
        var first = page.get("items").get(0);
        assertThat(first.get("external_id").asText()).isEqualTo("list-000");
        assertThat(first.get("tax_lines").get(0).get("name").asText()).isEqualTo("T0");
        assertThat(first.get("fee_lines").get(0).get("name").asText()).isEqualTo("F0");
        // transactions without lines still return empty arrays, not nulls
        assertThat(page.get("items").get(1).get("tax_lines")).isEmpty();
    }

    // ------------------------------------------------------ plan test 17

    @Test
    void limitsAreBoundedAndInvalidCursorsAre400() {
        seedTransactions(120);

        assertThat(get("/transactions?limit=0").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=-5").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=101").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=1000").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=abc").status()).isEqualTo(400);

        var atMax = get("/transactions?limit=100").body();
        assertThat(atMax.get("items")).hasSize(100);
        assertThat(atMax.get("next_cursor").isNull()).isFalse();

        HttpResult garbage = get("/transactions?cursor=garbage");
        assertThat(garbage.status()).isEqualTo(400);
        assertThat(garbage.error()).isEqualTo("invalid_cursor");

        // a well-formed cursor from another list still parses (keyset, not offset)
        var one = get("/transactions?limit=1").body();
        assertThat(one.get("items")).hasSize(1);
        String cursor = one.get("next_cursor").asText();
        var afterOne = get("/transactions?limit=100&cursor=" + cursor).body();
        assertThat(afterOne.get("items")).hasSize(100);
        assertThat(afterOne.get("items").get(0).get("id").asText())
                .isNotEqualTo(one.get("items").get(0).get("id").asText());
    }

    @Test
    void keysetPagesStayStableWhenRowsAreInsertedInBetween() {
        seedTransactions(60);
        var page1 = get("/transactions?limit=50").body();
        assertThat(page1.get("items")).hasSize(50);
        String cursor = page1.get("next_cursor").asText();

        // rows that land after the cursor but before the unread tail, and rows
        // after the whole seeded set — a keyset list must keep serving the
        // original tail exactly once
        insertRows(60, 5, base.plusSeconds(49).plusNanos(500_000_000), Duration.ofMillis(1));
        insertRows(65, 5, base.plusSeconds(120), Duration.ofSeconds(1));

        Set<String> page1Originals = new HashSet<>();
        page1.get("items").forEach(item -> page1Originals.add(item.get("external_id").asText()));
        assertThat(page1Originals).hasSize(50).allMatch(ext -> ext.compareTo("list-050") < 0);

        var page2 = get("/transactions?limit=100&cursor=" + cursor).body();
        Set<String> page2ExternalIds = new HashSet<>();
        page2.get("items").forEach(item -> page2ExternalIds.add(item.get("external_id").asText()));

        // every remaining original row appears exactly once and never repeats
        for (int i = 50; i < 60; i++) {
            assertThat(page2ExternalIds)
                    .as("original row list-%03d must survive interleaved inserts", i)
                    .contains("list-%03d".formatted(i));
        }
        page2ExternalIds.retainAll(page1Originals);
        assertThat(page2ExternalIds).as("no row may appear on both pages").isEmpty();

        // and all 60 originals were seen exactly once across the two pages
        Set<String> allOriginals = new HashSet<>(page1Originals);
        page2.get("items").forEach(item -> {
            String externalId = item.get("external_id").asText();
            if (externalId.compareTo("list-060") < 0) {
                assertThat(allOriginals.add(externalId))
                        .as("original %s appeared twice", externalId)
                        .isTrue();
            }
        });
        assertThat(allOriginals).hasSize(60);
    }

    // ------------------------------------------------------------ helpers

    /** Start of the seeded created_at sequence; set by {@link #seedTransactions(int)}. */
    private LocalDateTime base;

    /**
     * Bulk-inserts {@code count} list rows in request order: created_at advances
     * one second per row and every tenth row carries one tax and one fee line so
     * hydration of child rows is covered too.
     */
    private void seedTransactions(int count) {
        base = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        insertRows(0, count, base, Duration.ofSeconds(1));
    }

    /** Inserts rows numbered from {@code firstIndex}, created_at stepping by {@code step}. */
    private void insertRows(int firstIndex, int count, LocalDateTime start, Duration step) {
        List<StoredTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = firstIndex + i;
            LocalDateTime createdAt = start.plus(step.multipliedBy(i));
            boolean withLines = index % 10 == 0;
            transactions.add(new StoredTransaction(
                    "txn_list_%03d".formatted(index),
                    "list-%03d".formatted(index),
                    TransactionType.FLIGHT,
                    "EUR",
                    createdAt,
                    1000L + index,
                    null,
                    objectMapper.createObjectNode(),
                    createdAt,
                    withLines
                            ? List.of(new TaxLine("T" + index, new java.math.BigDecimal("0.0700"), 50L))
                            : List.of(),
                    withLines ? List.of(new FeeLine("F" + index, 25L)) : List.of()));
        }
        transactionRepository.insertAll(transactions);
    }
}
