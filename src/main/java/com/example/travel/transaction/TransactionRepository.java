package com.example.travel.transaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.travel.domain.FeeKind;
import com.example.travel.domain.FeeLine;
import com.example.travel.domain.TaxLine;
import com.example.travel.domain.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * All transaction persistence. No query is unbounded: point lookups use the
 * unique external_id / primary key, lists use keyset (seek) pagination with
 * LIMIT, and multi-id loads are chunked IN (...) lists.
 *
 * List queries return headers with empty lines; hydrate pages with
 * {@link #findTaxLinesByTransactionIds} / {@link #findFeeLinesByTransactionIds}
 * (one IN per table, never N+1).
 */
@Repository
public class TransactionRepository {

    private static final String COLUMNS =
            "id, external_id, type, currency, occurred_at, total_minor, fee_kind, metadata, created_at, updated_at";

    private static final String INSERT_HEADER = """
            INSERT INTO transactions (id, external_id, type, currency, occurred_at, total_minor, fee_kind, metadata, created_at, updated_at)
            VALUES (:id, :externalId, :type, :currency, :occurredAt, :totalMinor, :feeKind, :metadata, :createdAt, :updatedAt)
            """;

    private static final String INSERT_TAX_LINE = """
            INSERT INTO transaction_tax_lines (transaction_id, position, name, rate, amount_minor)
            VALUES (:transactionId, :position, :name, :rate, :amountMinor)
            """;

    private static final String INSERT_FEE_LINE = """
            INSERT INTO transaction_fee_lines (transaction_id, position, name, amount_minor)
            VALUES (:transactionId, :position, :name, :amountMinor)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- reads

    public Optional<StoredTransaction> findById(String id) {
        return findOne("SELECT " + COLUMNS + " FROM transactions WHERE id = :id", new MapSqlParameterSource().addValue("id", id));
    }

    public Optional<StoredTransaction> findByExternalId(String externalId) {
        return findOne("SELECT " + COLUMNS + " FROM transactions WHERE external_id = :externalId",
                new MapSqlParameterSource().addValue("externalId", externalId));
    }

    private Optional<StoredTransaction> findOne(String sql, MapSqlParameterSource params) {
        List<StoredTransaction> rows = jdbc.query(sql, params, headerMapper());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredTransaction header = rows.getFirst();
        Map<String, List<TaxLine>> taxes = findTaxLinesByTransactionIds(List.of(header.id()));
        Map<String, List<FeeLine>> fees = findFeeLinesByTransactionIds(List.of(header.id()));
        return Optional.of(withLines(header, taxes.getOrDefault(header.id(), List.of()), fees.getOrDefault(header.id(), List.of())));
    }

    /** Loads and locks a chunk of transactions (id order = global lock order). */
    public List<StoredTransaction> findByIdsForUpdate(Collection<String> ids) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transactions WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                new MapSqlParameterSource().addValue("ids", ids),
                headerMapper());
    }

    public Map<String, List<TaxLine>> findTaxLinesByTransactionIds(Collection<String> ids) {
        Map<String, List<TaxLine>> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT transaction_id, name, rate, amount_minor FROM transaction_tax_lines "
                        + "WHERE transaction_id IN (:ids) ORDER BY transaction_id, position",
                new MapSqlParameterSource().addValue("ids", ids),
                rs -> {
                    result.computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
                            .add(new TaxLine(rs.getString("name"), rs.getBigDecimal("rate"), rs.getLong("amount_minor")));
                });
        return result;
    }

    public Map<String, List<FeeLine>> findFeeLinesByTransactionIds(Collection<String> ids) {
        Map<String, List<FeeLine>> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT transaction_id, name, amount_minor FROM transaction_fee_lines "
                        + "WHERE transaction_id IN (:ids) ORDER BY transaction_id, position",
                new MapSqlParameterSource().addValue("ids", ids),
                rs -> {
                    result.computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
                            .add(new FeeLine(rs.getString("name"), rs.getLong("amount_minor")));
                });
        return result;
    }

    /** Keyset page: (created_at, id) seek, headers only, LIMIT limit (caller passes limit+1). */
    public List<StoredTransaction> listFirstPage(int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transactions ORDER BY created_at, id LIMIT :limit",
                new MapSqlParameterSource().addValue("limit", limit),
                headerMapper());
    }

    public List<StoredTransaction> listPageAfter(LocalDateTime createdAt, String id, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transactions "
                        + "WHERE (created_at > :createdAt OR (created_at = :createdAt AND id > :id)) "
                        + "ORDER BY created_at, id LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("createdAt", createdAt)
                        .addValue("id", id)
                        .addValue("limit", limit),
                headerMapper());
    }

    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM transactions",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    // --------------------------------------------------------------- writes

    /** Inserts header + lines with batchUpdate. Caller owns the transaction. */
    public void insert(StoredTransaction transaction) {
        jdbc.update(INSERT_HEADER, headerParams(transaction));
        insertLines(transaction.id(), transaction.taxLines(), transaction.feeLines());
    }

    /** Bulk insert (tests): headers, then lines in one batch per table. */
    public void insertAll(List<StoredTransaction> transactions) {
        MapSqlParameterSource[] headers = transactions.stream().map(this::headerParams).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_HEADER, headers);

        List<MapSqlParameterSource> taxes = new ArrayList<>();
        List<MapSqlParameterSource> fees = new ArrayList<>();
        for (StoredTransaction transaction : transactions) {
            for (int i = 0; i < transaction.taxLines().size(); i++) {
                TaxLine line = transaction.taxLines().get(i);
                taxes.add(new MapSqlParameterSource()
                        .addValue("transactionId", transaction.id())
                        .addValue("position", i)
                        .addValue("name", line.name())
                        .addValue("rate", line.rate())
                        .addValue("amountMinor", line.amountMinor()));
            }
            for (int i = 0; i < transaction.feeLines().size(); i++) {
                FeeLine line = transaction.feeLines().get(i);
                fees.add(new MapSqlParameterSource()
                        .addValue("transactionId", transaction.id())
                        .addValue("position", i)
                        .addValue("name", line.name())
                        .addValue("amountMinor", line.amountMinor()));
            }
        }
        if (!taxes.isEmpty()) {
            jdbc.batchUpdate(INSERT_TAX_LINE, taxes.toArray(MapSqlParameterSource[]::new));
        }
        if (!fees.isEmpty()) {
            jdbc.batchUpdate(INSERT_FEE_LINE, fees.toArray(MapSqlParameterSource[]::new));
        }
    }

    /** Replaces occurred_at, total, metadata, fee_kind, both line lists and updated_at. Caller owns the transaction. */
    public void replaceContent(String id, LocalDateTime occurredAt, long totalMinor, String metadataJson,
                               FeeKind feeKind, LocalDateTime updatedAt,
                               List<TaxLine> taxLines, List<FeeLine> feeLines) {
        jdbc.update(
                "UPDATE transactions SET occurred_at = :occurredAt, total_minor = :totalMinor, "
                        + "metadata = :metadata, fee_kind = :feeKind, updated_at = :updatedAt WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("occurredAt", occurredAt)
                        .addValue("totalMinor", totalMinor)
                        .addValue("metadata", metadataJson)
                        .addValue("feeKind", feeKind == null ? null : feeKind.wire())
                        .addValue("updatedAt", updatedAt)
                        .addValue("id", id));
        jdbc.update("DELETE FROM transaction_tax_lines WHERE transaction_id = :id",
                new MapSqlParameterSource().addValue("id", id));
        jdbc.update("DELETE FROM transaction_fee_lines WHERE transaction_id = :id",
                new MapSqlParameterSource().addValue("id", id));
        insertLines(id, taxLines, feeLines);
    }

    /** Deletes header; tax/fee lines go via ON DELETE CASCADE. Caller owns the transaction. */
    public void delete(String id) {
        jdbc.update("DELETE FROM transactions WHERE id = :id", new MapSqlParameterSource().addValue("id", id));
    }

    /** Cheap invoiced-check backed by idx_invoice_items_tx. */
    public boolean isOnInvoice(String transactionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice_items WHERE transaction_id = :id",
                new MapSqlParameterSource().addValue("id", transactionId), Integer.class);
        return count != null && count > 0;
    }

    private void insertLines(String transactionId, List<TaxLine> taxLines, List<FeeLine> feeLines) {
        if (!taxLines.isEmpty()) {
            MapSqlParameterSource[] params = new MapSqlParameterSource[taxLines.size()];
            for (int i = 0; i < taxLines.size(); i++) {
                TaxLine line = taxLines.get(i);
                params[i] = new MapSqlParameterSource()
                        .addValue("transactionId", transactionId)
                        .addValue("position", i)
                        .addValue("name", line.name())
                        .addValue("rate", line.rate())
                        .addValue("amountMinor", line.amountMinor());
            }
            jdbc.batchUpdate(INSERT_TAX_LINE, params);
        }
        if (!feeLines.isEmpty()) {
            MapSqlParameterSource[] params = new MapSqlParameterSource[feeLines.size()];
            for (int i = 0; i < feeLines.size(); i++) {
                FeeLine line = feeLines.get(i);
                params[i] = new MapSqlParameterSource()
                        .addValue("transactionId", transactionId)
                        .addValue("position", i)
                        .addValue("name", line.name())
                        .addValue("amountMinor", line.amountMinor());
            }
            jdbc.batchUpdate(INSERT_FEE_LINE, params);
        }
    }

    private MapSqlParameterSource headerParams(StoredTransaction transaction) {
        return new MapSqlParameterSource()
                .addValue("id", transaction.id())
                .addValue("externalId", transaction.externalId())
                .addValue("type", transaction.type().wire())
                .addValue("currency", transaction.currency())
                .addValue("occurredAt", transaction.occurredAt())
                .addValue("totalMinor", transaction.totalMinor())
                .addValue("feeKind", transaction.feeKind() == null ? null : transaction.feeKind().wire())
                .addValue("metadata", objectMapper.writeValueAsString(transaction.metadata()))
                .addValue("createdAt", transaction.createdAt())
                .addValue("updatedAt", transaction.updatedAt());
    }

    private RowMapper<StoredTransaction> headerMapper() {
        return (rs, rowNum) -> mapHeader(rs, List.of(), List.of());
    }

    private StoredTransaction mapHeader(ResultSet rs, List<TaxLine> taxLines, List<FeeLine> feeLines) throws SQLException {
        String type = rs.getString("type");
        String feeKind = rs.getString("fee_kind");
        String metadata = rs.getString("metadata");
        return new StoredTransaction(
                rs.getString("id"),
                rs.getString("external_id"),
                TransactionType.fromWire(type),
                rs.getString("currency"),
                rs.getObject("occurred_at", LocalDateTime.class),
                rs.getLong("total_minor"),
                feeKind == null ? null : FeeKind.fromWire(feeKind),
                objectMapper.readTree(metadata),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                taxLines,
                feeLines);
    }

    private static StoredTransaction withLines(StoredTransaction header, List<TaxLine> taxLines, List<FeeLine> feeLines) {
        return new StoredTransaction(header.id(), header.externalId(), header.type(), header.currency(),
                header.occurredAt(), header.totalMinor(), header.feeKind(), header.metadata(),
                header.createdAt(), header.updatedAt(),
                taxLines, feeLines);
    }
}
