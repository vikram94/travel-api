package com.example.travel.invoice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.travel.common.Chunks;
import com.example.travel.invoice.InvoiceSnapshot.SnapshotItem;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Invoice persistence: header, frozen items, and stored rollups. Reads for
 * GET /invoices/{id} touch only these tables (never transactions), so cost is
 * independent of how many transactions back the invoice. Batch inserts run in
 * chunks of 500.
 */
@Repository
public class InvoiceRepository {

    static final int BATCH_SIZE = 500;

    private static final String HEADER_COLUMNS = "id, currency, total_minor, item_count, created_at, updated_at";

    private static final String INSERT_HEADER = """
            INSERT INTO invoices (id, currency, total_minor, item_count, txn_set_hash, created_at, updated_at)
            VALUES (:id, :currency, :totalMinor, :itemCount, :txnSetHash, :createdAt, :updatedAt)
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO invoice_items (invoice_id, position, transaction_id, external_id, type, currency,
                                       occurred_at, total_minor, fee_kind, metadata_snapshot,
                                       tax_lines_snapshot, fee_lines_snapshot)
            VALUES (:invoiceId, :position, :transactionId, :externalId, :type, :currency,
                    :occurredAt, :totalMinor, :feeKind, :metadataSnapshot, :taxLinesSnapshot, :feeLinesSnapshot)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public InvoiceRepository(NamedParameterJdbcTemplate jdbc, tools.jackson.databind.ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------ header lookups

    public Optional<String> findIdByHash(String txnSetHash) {
        List<String> ids = jdbc.query("SELECT id FROM invoices WHERE txn_set_hash = :hash",
                new MapSqlParameterSource().addValue("hash", txnSetHash),
                (rs, rowNum) -> rs.getString("id"));
        return ids.stream().findFirst();
    }

    public Optional<String> findIdByHashExcluding(String txnSetHash, String excludeId) {
        List<String> ids = jdbc.query(
                "SELECT id FROM invoices WHERE txn_set_hash = :hash AND id <> :excludeId",
                new MapSqlParameterSource().addValue("hash", txnSetHash).addValue("excludeId", excludeId),
                (rs, rowNum) -> rs.getString("id"));
        return ids.stream().findFirst();
    }

    public Optional<InvoiceHeader> findById(String id) {
        List<InvoiceHeader> rows = jdbc.query(
                "SELECT " + HEADER_COLUMNS + " FROM invoices WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id),
                (rs, rowNum) -> mapHeader(rs));
        return rows.stream().findFirst();
    }

    /** Locks the invoice row for the duration of the current transaction. */
    public boolean lockForUpdate(String id) {
        List<String> rows = jdbc.query(
                "SELECT id FROM invoices WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource().addValue("id", id),
                (rs, rowNum) -> rs.getString("id"));
        return !rows.isEmpty();
    }

    // -------------------------------------------------------------- items

    /** One page of items: position > cursor, ordered, LIMIT limit+1 semantics on the caller side. */
    public List<InvoiceItemRow> findItemsPage(String invoiceId, int afterPosition, int limit) {
        return jdbc.query(
                "SELECT position, transaction_id, external_id, type, currency, occurred_at, total_minor, "
                        + "fee_kind, metadata_snapshot, tax_lines_snapshot, fee_lines_snapshot "
                        + "FROM invoice_items WHERE invoice_id = :invoiceId AND position > :afterPosition "
                        + "ORDER BY position LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("invoiceId", invoiceId)
                        .addValue("afterPosition", afterPosition)
                        .addValue("limit", limit),
                (rs, rowNum) -> new InvoiceItemRow(
                        rs.getInt("position"),
                        rs.getString("transaction_id"),
                        rs.getString("external_id"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getObject("occurred_at", LocalDateTime.class),
                        rs.getLong("total_minor"),
                        rs.getString("fee_kind"),
                        rs.getString("metadata_snapshot"),
                        rs.getString("tax_lines_snapshot"),
                        rs.getString("fee_lines_snapshot")));
    }

    public List<RollupAccumulator.TaxRollupLine> findTaxRollup(String invoiceId) {
        return jdbc.query(
                "SELECT name, rate, amount_minor FROM invoice_tax_rollup WHERE invoice_id = :invoiceId ORDER BY position",
                new MapSqlParameterSource().addValue("invoiceId", invoiceId),
                (rs, rowNum) -> new RollupAccumulator.TaxRollupLine(
                        rs.getString("name"), rs.getBigDecimal("rate"), rs.getLong("amount_minor")));
    }

    public List<RollupAccumulator.FeeRollupLine> findFeeRollup(String invoiceId) {
        return jdbc.query(
                "SELECT name, amount_minor FROM invoice_fee_rollup WHERE invoice_id = :invoiceId ORDER BY position",
                new MapSqlParameterSource().addValue("invoiceId", invoiceId),
                (rs, rowNum) -> new RollupAccumulator.FeeRollupLine(
                        rs.getString("name"), rs.getLong("amount_minor")));
    }

    // -------------------------------------------------------------- lists

    public List<InvoiceHeader> listFirstPage(int limit) {
        return jdbc.query(
                "SELECT " + HEADER_COLUMNS + " FROM invoices ORDER BY created_at, id LIMIT :limit",
                new MapSqlParameterSource().addValue("limit", limit),
                (rs, rowNum) -> mapHeader(rs));
    }

    public List<InvoiceHeader> listPageAfter(LocalDateTime createdAt, String id, int limit) {
        return jdbc.query(
                "SELECT " + HEADER_COLUMNS + " FROM invoices "
                        + "WHERE (created_at > :createdAt OR (created_at = :createdAt AND id > :id)) "
                        + "ORDER BY created_at, id LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("createdAt", createdAt)
                        .addValue("id", id)
                        .addValue("limit", limit),
                (rs, rowNum) -> mapHeader(rs));
    }

    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM invoices", new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------- writes

    /** Caller owns the transaction; a concurrent identical set surfaces as DuplicateKeyException. */
    public void insertHeader(String id, String currency, long totalMinor, int itemCount,
                             String txnSetHash, LocalDateTime createdAt, LocalDateTime updatedAt) {
        jdbc.update(INSERT_HEADER, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("currency", currency)
                .addValue("totalMinor", totalMinor)
                .addValue("itemCount", itemCount)
                .addValue("txnSetHash", txnSetHash)
                .addValue("createdAt", createdAt)
                .addValue("updatedAt", updatedAt));
    }

    public void insertItems(String invoiceId, List<InvoiceSnapshot.SnapshotItem> items) {
        for (List<SnapshotItem> batch : Chunks.chunk(items, BATCH_SIZE)) {
            MapSqlParameterSource[] params = new MapSqlParameterSource[batch.size()];
            for (int i = 0; i < batch.size(); i++) {
                SnapshotItem item = batch.get(i);
                params[i] = itemParams(invoiceId, item);
            }
            jdbc.batchUpdate(INSERT_ITEM, params);
        }
    }

    public void insertTaxRollup(String invoiceId, List<RollupAccumulator.TaxRollupLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] params = new MapSqlParameterSource[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            RollupAccumulator.TaxRollupLine line = lines.get(i);
            params[i] = new MapSqlParameterSource()
                    .addValue("invoiceId", invoiceId)
                    .addValue("position", i)
                    .addValue("name", line.name())
                    .addValue("rate", line.rate())
                    .addValue("amountMinor", line.amountMinor());
        }
        jdbc.batchUpdate(
                "INSERT INTO invoice_tax_rollup (invoice_id, position, name, rate, amount_minor) "
                        + "VALUES (:invoiceId, :position, :name, :rate, :amountMinor)",
                params);
    }

    public void insertFeeRollup(String invoiceId, List<RollupAccumulator.FeeRollupLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] params = new MapSqlParameterSource[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            RollupAccumulator.FeeRollupLine line = lines.get(i);
            params[i] = new MapSqlParameterSource()
                    .addValue("invoiceId", invoiceId)
                    .addValue("position", i)
                    .addValue("name", line.name())
                    .addValue("amountMinor", line.amountMinor());
        }
        jdbc.batchUpdate(
                "INSERT INTO invoice_fee_rollup (invoice_id, position, name, amount_minor) "
                        + "VALUES (:invoiceId, :position, :name, :amountMinor)",
                params);
    }

    public void deleteItemsAndRollups(String invoiceId) {
        jdbc.update("DELETE FROM invoice_items WHERE invoice_id = :invoiceId",
                new MapSqlParameterSource().addValue("invoiceId", invoiceId));
        jdbc.update("DELETE FROM invoice_tax_rollup WHERE invoice_id = :invoiceId",
                new MapSqlParameterSource().addValue("invoiceId", invoiceId));
        jdbc.update("DELETE FROM invoice_fee_rollup WHERE invoice_id = :invoiceId",
                new MapSqlParameterSource().addValue("invoiceId", invoiceId));
    }

    public void updateHeader(String id, String currency, long totalMinor, int itemCount,
                             String txnSetHash, LocalDateTime updatedAt) {
        jdbc.update(
                "UPDATE invoices SET currency = :currency, total_minor = :totalMinor, "
                        + "item_count = :itemCount, txn_set_hash = :txnSetHash, updated_at = :updatedAt WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("currency", currency)
                        .addValue("totalMinor", totalMinor)
                        .addValue("itemCount", itemCount)
                        .addValue("txnSetHash", txnSetHash)
                        .addValue("updatedAt", updatedAt)
                        .addValue("id", id));
    }

    /** Deletes header; items and rollups go via ON DELETE CASCADE. Transactions are untouched. */
    public void delete(String id) {
        jdbc.update("DELETE FROM invoices WHERE id = :id", new MapSqlParameterSource().addValue("id", id));
    }

    // ------------------------------------------------------------ helpers

    private MapSqlParameterSource itemParams(String invoiceId, SnapshotItem item) {
        var transaction = item.transaction();
        return new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("position", item.position())
                .addValue("transactionId", transaction.id())
                .addValue("externalId", transaction.externalId())
                .addValue("type", transaction.type().wire())
                .addValue("currency", transaction.currency())
                .addValue("occurredAt", transaction.occurredAt())
                .addValue("totalMinor", transaction.totalMinor())
                .addValue("feeKind", transaction.feeKind() == null ? null : transaction.feeKind().wire())
                .addValue("metadataSnapshot", objectMapper.writeValueAsString(transaction.metadata()))
                .addValue("taxLinesSnapshot", item.taxLinesJson())
                .addValue("feeLinesSnapshot", item.feeLinesJson());
    }

    private InvoiceHeader mapHeader(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InvoiceHeader(
                rs.getString("id"),
                rs.getString("currency"),
                rs.getLong("total_minor"),
                rs.getInt("item_count"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
