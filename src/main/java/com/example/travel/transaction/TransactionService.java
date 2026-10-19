package com.example.travel.transaction;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.travel.api.dto.CreateTransactionRequest;
import com.example.travel.api.dto.FeeLineResponse;
import com.example.travel.api.dto.PatchTransactionRequest;
import com.example.travel.api.dto.TaxLineResponse;
import com.example.travel.api.dto.TransactionListResponse;
import com.example.travel.api.dto.TransactionResponse;
import com.example.travel.common.Cursor;
import com.example.travel.common.Pagination;
import com.example.travel.domain.ConflictException;
import com.example.travel.domain.FeeLine;
import com.example.travel.domain.Money;
import com.example.travel.domain.NotFoundException;
import com.example.travel.domain.TaxLine;
import com.example.travel.domain.ValidationException;
import com.example.travel.transaction.StoredTransaction;
import com.example.travel.validation.TransactionValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Transaction use cases. Header + lines are written atomically inside one
 * transaction; the duplicate-external_id conflict is handled OUTSIDE the
 * failed transaction by re-reading the committed winner.
 */
@Service
public class TransactionService {

    public static final int LIST_DEFAULT_LIMIT = 50;
    public static final int LIST_MAX_LIMIT = 100;

    private final TransactionRepository transactionRepository;
    private final TransactionValidator transactionValidator;
    private final DuplicateIdentityHandler duplicateIdentityHandler;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionValidator transactionValidator,
                              DuplicateIdentityHandler duplicateIdentityHandler,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.transactionValidator = transactionValidator;
        this.duplicateIdentityHandler = duplicateIdentityHandler;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public TransactionResponse create(CreateTransactionRequest request) {
        // validate/hash/JSON before the DB transaction where possible
        TransactionValidator.Parsed parsed = transactionValidator.parse(request);
        LocalDateTime now = nowUtc();
        StoredTransaction toInsert = new StoredTransaction(
                newId(), parsed.externalId(), parsed.type(), parsed.currency(), parsed.occurredAt(),
                parsed.totalMinor(), parsed.feeKind(), parsed.metadata(), now, now,
                parsed.taxLines(), parsed.feeLines());
        try {
            transactionTemplate.executeWithoutResult(status -> transactionRepository.insert(toInsert));
        } catch (DuplicateKeyException e) {
            throw duplicateIdentityHandler.conflict(e, parsed.externalId());
        }
        return responseOf(transactionRepository.findById(toInsert.id())
                .orElseThrow(() -> new IllegalStateException("transaction vanished after insert: " + toInsert.id())));
    }

    public TransactionResponse get(String id) {
        return responseOf(transactionRepository.findById(id).orElseThrow(() -> notFound(id)));
    }

    /**
     * Partial PATCH: absent fields keep their stored value, present fields replace
     * theirs (an empty tax_lines/fee_lines list clears it). Locks the row, rejects
     * immutable-field changes, then refuses with 409 when the transaction is on any
     * invoice (snapshots stay frozen).
     */
    public TransactionResponse patch(String id, PatchTransactionRequest request) {
        return transactionTemplate.execute(status -> {
            // lockForUpdate loads the header only; hydrate the line lists for the merge
            lockForUpdate(id);
            StoredTransaction existing = transactionRepository.findById(id).orElseThrow(() -> notFound(id));
            checkImmutable(existing, request);
            if (transactionRepository.isOnInvoice(id)) {
                throw ConflictException.transactionInvoiced(id);
            }
            TransactionValidator.Merged merged = transactionValidator.mergeInto(existing, request);
            transactionRepository.replaceContent(id, merged.occurredAt(), merged.totalMinor(),
                    objectMapper.writeValueAsString(merged.metadata()), merged.feeKind(), nowUtc(),
                    merged.taxLines(), merged.feeLines());
            return responseOf(transactionRepository.findById(id).orElseThrow(() -> notFound(id)));
        });
    }

    public void delete(String id) {
        transactionTemplate.executeWithoutResult(status -> {
            lockForUpdate(id);
            if (transactionRepository.isOnInvoice(id)) {
                throw ConflictException.transactionInvoiced(id);
            }
            transactionRepository.delete(id);
        });
    }

    public TransactionListResponse list(String limitParam, String cursorParam) {
        int limit = Pagination.parseLimit(limitParam, LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT, "limit");
        Cursor cursor = Pagination.parseCursor(cursorParam);
        List<StoredTransaction> page = cursor == null
                ? transactionRepository.listFirstPage(limit + 1)
                : transactionRepository.listPageAfter(cursor.createdAt(), cursor.id(), limit + 1);

        boolean hasMore = page.size() > limit;
        if (hasMore) {
            page = page.subList(0, limit);
        }
        String nextCursor = hasMore && !page.isEmpty()
                ? Cursor.encode(page.getLast().createdAt(), page.getLast().id())
                : null;

        List<String> ids = page.stream().map(StoredTransaction::id).toList();
        Map<String, List<TaxLine>> taxes = ids.isEmpty() ? Map.of() : transactionRepository.findTaxLinesByTransactionIds(ids);
        Map<String, List<FeeLine>> fees = ids.isEmpty() ? Map.of() : transactionRepository.findFeeLinesByTransactionIds(ids);
        List<TransactionResponse> items = page.stream()
                .map(t -> responseOf(t, taxes.getOrDefault(t.id(), List.of()), fees.getOrDefault(t.id(), List.of())))
                .toList();
        return new TransactionListResponse(items, nextCursor);
    }

    // --------------------------------------------------------------- helpers

    private StoredTransaction lockForUpdate(String id) {
        List<StoredTransaction> rows = transactionRepository.findByIdsForUpdate(List.of(id));
        if (rows.isEmpty()) {
            throw notFound(id);
        }
        return rows.getFirst();
    }

    private static void checkImmutable(StoredTransaction existing, PatchTransactionRequest request) {
        if (request.type() != null && !request.type().equals(existing.type().wire())) {
            throw new ValidationException("type", "is immutable and cannot be changed");
        }
        if (request.externalId() != null && !request.externalId().equals(existing.externalId())) {
            throw new ValidationException("external_id", "is immutable and cannot be changed");
        }
        if (request.currency() != null && !request.currency().equals(existing.currency())) {
            throw new ValidationException("currency", "is immutable and cannot be changed");
        }
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("transaction " + id + " not found");
    }

    static String newId() {
        return "txn_" + UUID.randomUUID();
    }

    static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    static TransactionResponse responseOf(StoredTransaction transaction) {
        return responseOf(transaction, transaction.taxLines(), transaction.feeLines());
    }

    static TransactionResponse responseOf(StoredTransaction transaction, List<TaxLine> taxLines, List<FeeLine> feeLines) {
        return new TransactionResponse(
                transaction.id(),
                transaction.type().wire(),
                transaction.externalId(),
                transaction.currency(),
                transaction.occurredAt().atZone(ZoneOffset.UTC).toInstant(),
                Money.ofMinor(transaction.totalMinor()).toDecimal(),
                taxLines.stream()
                        .map(line -> new TaxLineResponse(line.name(), line.rate(), Money.ofMinor(line.amountMinor()).toDecimal()))
                        .toList(),
                feeLines.stream()
                        .map(line -> new FeeLineResponse(line.name(), Money.ofMinor(line.amountMinor()).toDecimal()))
                        .toList(),
                transaction.metadata(),
                transaction.createdAt().atZone(ZoneOffset.UTC).toInstant(),
                transaction.updatedAt().atZone(ZoneOffset.UTC).toInstant());
    }
}
