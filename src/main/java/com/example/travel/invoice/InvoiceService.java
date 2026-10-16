package com.example.travel.invoice;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.example.travel.api.dto.CreateInvoiceRequest;
import com.example.travel.api.dto.FeeLineResponse;
import com.example.travel.api.dto.FeeRollupResponse;
import com.example.travel.api.dto.InvoiceHeaderResponse;
import com.example.travel.api.dto.InvoiceItemResponse;
import com.example.travel.api.dto.InvoiceListResponse;
import com.example.travel.api.dto.InvoiceResponse;
import com.example.travel.api.dto.TaxLineResponse;
import com.example.travel.api.dto.TaxRollupResponse;
import com.example.travel.common.Chunks;
import com.example.travel.common.Cursor;
import com.example.travel.common.Hashing;
import com.example.travel.common.Pagination;
import com.example.travel.domain.ConflictException;
import com.example.travel.domain.FeeLine;
import com.example.travel.domain.InvalidCursorException;
import com.example.travel.domain.Money;
import com.example.travel.domain.NotFoundException;
import com.example.travel.domain.TaxLine;
import com.example.travel.domain.TransactionNotFoundException;
import com.example.travel.domain.TooManyTransactionsException;
import com.example.travel.domain.ValidationException;
import com.example.travel.invoice.InvoiceSnapshot.SnapshotItem;
import com.example.travel.transaction.StoredTransaction;
import com.example.travel.transaction.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Frozen invoices.
 *
 * Create runs exactly: validate shape/cap/dups in memory -> SHA-256 of sorted
 * ids -> lock-free fast path on txn_set_hash -> one DB transaction that locks
 * the transactions in ascending id order (chunked FOR UPDATE), builds the
 * snapshot in request order, and batch-inserts header + items + rollups.
 * Concurrent identical creates lose on the unique txn_set_hash index and are
 * answered 409 with the winner's id.
 */
@Service
public class InvoiceService {

    public static final int LIST_DEFAULT_LIMIT = 50;
    public static final int LIST_MAX_LIMIT = 100;

    private final InvoiceRepository invoiceRepository;
    private final TransactionRepository transactionRepository;
    private final InvoiceSnapshotBuilder snapshotBuilder;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @Value("${invoice.max-transactions}")
    private int maxTransactions;

    @Value("${invoice.load-chunk-size}")
    private int loadChunkSize;

    @Value("${invoice.items-page-default}")
    private int itemsPageDefault;

    @Value("${invoice.items-page-max}")
    private int itemsPageMax;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          TransactionRepository transactionRepository,
                          InvoiceSnapshotBuilder snapshotBuilder,
                          TransactionTemplate transactionTemplate,
                          ObjectMapper objectMapper) {
        this.invoiceRepository = invoiceRepository;
        this.transactionRepository = transactionRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    private record InvoiceBuild(InvoiceHeader header, InvoiceSnapshot snapshot) {
    }

    // -------------------------------------------------------------- create

    public InvoiceResponse create(CreateInvoiceRequest request) {
        List<String> ids = parseIds(request);
        String txnSetHash = Hashing.txnSetHash(ids);
        List<String> sorted = ids.stream().sorted().toList();

        // step 2: fast path, no locks — same set billed before?
        invoiceRepository.findIdByHash(txnSetHash)
                .ifPresent(existing -> { throw ConflictException.invoiceExistsForSet(existing); });

        InvoiceBuild build;
        try {
            build = transactionTemplate.execute(status -> {
                Map<String, StoredTransaction> loaded = loadAndLock(sorted);
                InvoiceSnapshot snapshot = snapshotBuilder.build(inRequestOrder(ids, loaded));
                LocalDateTime createdAt = nowUtc();
                String invoiceId = newInvoiceId();
                invoiceRepository.insertHeader(invoiceId, snapshot.currency(), snapshot.totalMinor(),
                        snapshot.items().size(), txnSetHash, createdAt);
                invoiceRepository.insertItems(invoiceId, snapshot.items());
                invoiceRepository.insertTaxRollup(invoiceId, snapshot.taxRollup());
                invoiceRepository.insertFeeRollup(invoiceId, snapshot.feeRollup());
                InvoiceHeader header = new InvoiceHeader(invoiceId, snapshot.currency(), snapshot.totalMinor(),
                        snapshot.items().size(), txnSetHash, createdAt);
                return new InvoiceBuild(header, snapshot);
            });
        } catch (DuplicateKeyException e) {
            // concurrent identical create: lost on the unique txn_set_hash index
            String winner = invoiceRepository.findIdByHash(txnSetHash).orElseThrow(() -> e);
            throw ConflictException.invoiceExistsForSet(winner);
        } catch (ArithmeticException e) {
            throw new ValidationException("transaction_ids", "invoice total exceeds the supported range");
        }
        return firstPageResponse(build.header(), build.snapshot());
    }

    // ----------------------------------------------------------------- get

    public InvoiceResponse get(String id, String itemsLimitParam, String itemsCursorParam) {
        int itemsLimit = Pagination.parseLimit(itemsLimitParam, itemsPageDefault, itemsPageMax, "items_limit");
        int afterPosition = parseItemsCursor(itemsCursorParam);
        InvoiceHeader header = invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("invoice " + id + " not found"));

        List<InvoiceItemRow> page = invoiceRepository.findItemsPage(id, afterPosition, itemsLimit + 1);
        boolean hasMore = page.size() > itemsLimit;
        if (hasMore) {
            page = page.subList(0, itemsLimit);
        }
        String nextCursor = hasMore && !page.isEmpty() ? String.valueOf(page.getLast().position()) : null;

        return responseOf(header,
                invoiceRepository.findTaxRollup(id),
                invoiceRepository.findFeeRollup(id),
                page.stream().map(this::toItem).toList(),
                nextCursor);
    }

    // --------------------------------------------------------------- patch

    /** Replaces the transaction set: locks the invoice first, then the transactions. */
    public InvoiceResponse patch(String id, CreateInvoiceRequest request) {
        List<String> ids = parseIds(request);
        String txnSetHash = Hashing.txnSetHash(ids);
        List<String> sorted = ids.stream().sorted().toList();

        InvoiceBuild build;
        try {
            build = transactionTemplate.execute(status -> {
                if (!invoiceRepository.lockForUpdate(id)) {
                    throw new NotFoundException("invoice " + id + " not found");
                }
                // another invoice already owns this set?
                invoiceRepository.findIdByHashExcluding(txnSetHash, id)
                        .ifPresent(other -> { throw ConflictException.invoiceExistsForSet(other); });

                Map<String, StoredTransaction> loaded = loadAndLock(sorted);
                InvoiceSnapshot snapshot = snapshotBuilder.build(inRequestOrder(ids, loaded));
                invoiceRepository.deleteItemsAndRollups(id);
                invoiceRepository.insertItems(id, snapshot.items());
                invoiceRepository.insertTaxRollup(id, snapshot.taxRollup());
                invoiceRepository.insertFeeRollup(id, snapshot.feeRollup());

                InvoiceHeader existing = invoiceRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("invoice " + id + " not found"));
                invoiceRepository.updateHeader(id, snapshot.currency(), snapshot.totalMinor(),
                        snapshot.items().size(), txnSetHash);
                InvoiceHeader header = new InvoiceHeader(id, snapshot.currency(), snapshot.totalMinor(),
                        snapshot.items().size(), txnSetHash, existing.createdAt());
                return new InvoiceBuild(header, snapshot);
            });
        } catch (DuplicateKeyException e) {
            String other = invoiceRepository.findIdByHashExcluding(txnSetHash, id).orElseThrow(() -> e);
            throw ConflictException.invoiceExistsForSet(other);
        } catch (ArithmeticException e) {
            throw new ValidationException("transaction_ids", "invoice total exceeds the supported range");
        }
        return firstPageResponse(build.header(), build.snapshot());
    }

    // -------------------------------------------------------------- delete

    /** Deletes the invoice and its snapshot; underlying transactions are untouched. */
    public void delete(String id) {
        transactionTemplate.executeWithoutResult(status -> {
            if (!invoiceRepository.lockForUpdate(id)) {
                throw new NotFoundException("invoice " + id + " not found");
            }
            invoiceRepository.delete(id);
        });
    }

    // ---------------------------------------------------------------- list

    public InvoiceListResponse list(String limitParam, String cursorParam) {
        int limit = Pagination.parseLimit(limitParam, LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT, "limit");
        Cursor cursor = Pagination.parseCursor(cursorParam);
        List<InvoiceHeader> page = cursor == null
                ? invoiceRepository.listFirstPage(limit + 1)
                : invoiceRepository.listPageAfter(cursor.createdAt(), cursor.id(), limit + 1);

        boolean hasMore = page.size() > limit;
        if (hasMore) {
            page = page.subList(0, limit);
        }
        String nextCursor = hasMore && !page.isEmpty()
                ? Cursor.encode(page.getLast().createdAt(), page.getLast().id())
                : null;

        List<InvoiceHeaderResponse> items = page.stream()
                .map(header -> new InvoiceHeaderResponse(header.id(), header.currency(),
                        Money.ofMinor(header.totalMinor()).toDecimal(), header.itemCount(),
                        header.txnSetHash(), header.createdAt().atZone(ZoneOffset.UTC).toInstant()))
                .toList();
        return new InvoiceListResponse(items, nextCursor);
    }

    // -------------------------------------------------------------- helpers

    /** Shape (exactly one id field, non-empty) -> cap -> repeated ids, all before any DB work. */
    private List<String> parseIds(CreateInvoiceRequest request) {
        boolean hasSingle = request.transactionId() != null;
        List<String> requested = request.transactionIds();
        boolean hasMany = requested != null && !requested.isEmpty();
        if (hasSingle == hasMany) {
            throw new ValidationException("transaction_ids",
                    "provide exactly one of transaction_id or a non-empty transaction_ids");
        }
        List<String> ids = hasSingle ? List.of(request.transactionId()) : List.copyOf(requested);
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new ValidationException("transaction_ids", "ids must be non-blank strings");
            }
        }
        if (ids.size() > maxTransactions) {
            throw new TooManyTransactionsException(maxTransactions);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> repeated = new ArrayList<>();
        for (String id : ids) {
            if (!seen.add(id) && !repeated.contains(id)) {
                repeated.add(id);
            }
        }
        if (!repeated.isEmpty()) {
            throw ConflictException.duplicateTransactionId(repeated);
        }
        return ids;
    }

    /**
     * Chunked FOR UPDATE load in ascending id order (the global lock order).
     * Missing ids -> 404 listing all of them; mixed currency fails fast while loading.
     */
    private Map<String, StoredTransaction> loadAndLock(List<String> sortedIds) {
        Map<String, StoredTransaction> loaded = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        Set<String> currencies = new TreeSet<>();
        for (List<String> chunk : Chunks.chunk(sortedIds, loadChunkSize)) {
            List<StoredTransaction> rows = transactionRepository.findByIdsForUpdate(chunk);
            Set<String> present = new HashSet<>();
            rows.forEach(row -> {
                present.add(row.id());
                currencies.add(row.currency());
            });
            if (currencies.size() > 1) {
                throw ConflictException.mixedCurrency(currencies);
            }
            Map<String, List<TaxLine>> taxes = transactionRepository.findTaxLinesByTransactionIds(chunk);
            Map<String, List<FeeLine>> fees = transactionRepository.findFeeLinesByTransactionIds(chunk);
            rows.forEach(row -> loaded.put(row.id(),
                    row.withLines(taxes.getOrDefault(row.id(), List.of()), fees.getOrDefault(row.id(), List.of()))));
            chunk.stream().filter(id -> !present.contains(id)).forEach(missing::add);
        }
        if (!missing.isEmpty()) {
            throw new TransactionNotFoundException(missing.stream().sorted().toList());
        }
        return loaded;
    }

    private static List<StoredTransaction> inRequestOrder(List<String> ids, Map<String, StoredTransaction> loaded) {
        return ids.stream().map(loaded::get).toList();
    }

    private static int parseItemsCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("items_cursor is not a valid position cursor");
        }
    }

    /** 201/200 body from memory (header + full rollups + first items page), no re-read. */
    private InvoiceResponse firstPageResponse(InvoiceHeader header, InvoiceSnapshot snapshot) {
        List<SnapshotItem> items = snapshot.items();
        boolean hasMore = items.size() > itemsPageDefault;
        List<SnapshotItem> page = hasMore ? items.subList(0, itemsPageDefault) : items;
        String nextCursor = hasMore && !page.isEmpty() ? String.valueOf(page.getLast().position()) : null;
        return responseOf(header, snapshot.taxRollup(), snapshot.feeRollup(),
                page.stream().map(item -> toItem(item.position(), item.transaction())).toList(),
                nextCursor);
    }

    private InvoiceResponse responseOf(InvoiceHeader header,
                                       List<RollupAccumulator.TaxRollupLine> taxRollup,
                                       List<RollupAccumulator.FeeRollupLine> feeRollup,
                                       List<InvoiceItemResponse> items,
                                       String itemsNextCursor) {
        return new InvoiceResponse(
                header.id(),
                header.currency(),
                Money.ofMinor(header.totalMinor()).toDecimal(),
                header.itemCount(),
                header.txnSetHash(),
                header.createdAt().atZone(ZoneOffset.UTC).toInstant(),
                taxRollup.stream()
                        .map(line -> new TaxRollupResponse(line.name(), line.rate(),
                                Money.ofMinor(line.amountMinor()).toDecimal()))
                        .toList(),
                feeRollup.stream()
                        .map(line -> new FeeRollupResponse(line.name(), Money.ofMinor(line.amountMinor()).toDecimal()))
                        .toList(),
                items,
                itemsNextCursor);
    }

    private InvoiceItemResponse toItem(int position, StoredTransaction transaction) {
        return new InvoiceItemResponse(
                position,
                transaction.id(),
                transaction.type().wire(),
                transaction.externalId(),
                transaction.currency(),
                transaction.occurredAt().atZone(ZoneOffset.UTC).toInstant(),
                Money.ofMinor(transaction.totalMinor()).toDecimal(),
                transaction.taxLines().stream()
                        .map(line -> new TaxLineResponse(line.name(), line.rate(), Money.ofMinor(line.amountMinor()).toDecimal()))
                        .toList(),
                transaction.feeLines().stream()
                        .map(line -> new FeeLineResponse(line.name(), Money.ofMinor(line.amountMinor()).toDecimal()))
                        .toList(),
                transaction.metadata());
    }

    private InvoiceItemResponse toItem(InvoiceItemRow row) {
        return new InvoiceItemResponse(
                row.position(),
                row.transactionId(),
                row.type(),
                row.externalId(),
                row.currency(),
                row.occurredAt().atZone(ZoneOffset.UTC).toInstant(),
                Money.ofMinor(row.totalMinor()).toDecimal(),
                List.of(objectMapper.readValue(row.taxLinesSnapshot(), TaxLineResponse[].class)),
                List.of(objectMapper.readValue(row.feeLinesSnapshot(), FeeLineResponse[].class)),
                objectMapper.readTree(row.metadataSnapshot()));
    }

    static String newInvoiceId() {
        return "inv_" + UUID.randomUUID();
    }

    static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
