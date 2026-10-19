package com.example.travel.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import com.example.travel.api.dto.CreateTransactionRequest;
import com.example.travel.api.dto.FeeLineRequest;
import com.example.travel.api.dto.PatchTransactionRequest;
import com.example.travel.api.dto.TaxLineRequest;
import com.example.travel.domain.FeeKind;
import com.example.travel.domain.FeeLine;
import com.example.travel.domain.Money;
import com.example.travel.domain.TaxLine;
import com.example.travel.domain.TransactionType;
import com.example.travel.domain.ValidationException;
import com.example.travel.domain.ValidationException.FieldError;
import com.example.travel.transaction.StoredTransaction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Validates one POST/PATCH transaction body and produces the parsed form the
 * repositories persist (minor units, normalized rate, UTC timestamps).
 * Collects every field-level problem and throws a single 400.
 */
@Component
public class TransactionValidator {

    public static final int MAX_TAX_LINES = 100;
    public static final int MAX_FEE_LINES = 100;
    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_METADATA_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;

    public TransactionValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Parsed request ready to persist; all money in minor units (scale 2). */
    public record Parsed(
            TransactionType type,
            String externalId,
            String currency,
            LocalDateTime occurredAt,
            long totalMinor,
            List<TaxLine> taxLines,
            List<FeeLine> feeLines,
            JsonNode metadata,
            FeeKind feeKind) {
    }

    /**
     * Result of a partial PATCH: a complete, ready-to-persist view where absent
     * fields were filled in from the stored row.
     */
    public record Merged(
            LocalDateTime occurredAt,
            long totalMinor,
            List<TaxLine> taxLines,
            List<FeeLine> feeLines,
            JsonNode metadata,
            FeeKind feeKind) {
    }

    public Parsed parse(CreateTransactionRequest request) {
        List<FieldError> errors = new ArrayList<>();

        TransactionType type = parseType(request.type(), errors);
        errors.addAll(ExternalIdRules.check(request.externalId(), "external_id"));
        String currency = parseCurrency(request.currency(), errors);
        LocalDateTime occurredAt = parseOccurredAt(request.occurredAt(), errors);
        long totalMinor = parseAmount(request.total(), "total", errors);
        List<TaxLine> taxLines = parseTaxLines(request.taxLines(), errors);
        List<FeeLine> feeLines = parseFeeLines(request.feeLines(), errors);
        FeeKind feeKind = validateMetadata(request.metadata(), type, errors);

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new Parsed(type, request.externalId(), currency, occurredAt, totalMinor,
                taxLines, feeLines, request.metadata(), feeKind);
    }

    /**
     * Partial PATCH: only the fields present in the body are validated; absent
     * fields keep the stored value. tax_lines / fee_lines are replace-whole, so a
     * present empty list clears them while an absent list is left untouched. The
     * type is immutable here, so it is taken from {@code existing}.
     */
    public Merged mergeInto(StoredTransaction existing, PatchTransactionRequest request) {
        List<FieldError> errors = new ArrayList<>();

        LocalDateTime occurredAt = request.occurredAt() == null
                ? existing.occurredAt()
                : parseOccurredAt(request.occurredAt(), errors);
        long totalMinor = request.total() == null
                ? existing.totalMinor()
                : parseAmount(request.total(), "total", errors);
        List<TaxLine> taxLines = request.taxLines() == null
                ? existing.taxLines()
                : parseTaxLines(request.taxLines(), errors);
        List<FeeLine> feeLines = request.feeLines() == null
                ? existing.feeLines()
                : parseFeeLines(request.feeLines(), errors);

        JsonNode metadata = existing.metadata();
        FeeKind feeKind = existing.feeKind();
        if (request.metadata() != null && !request.metadata().isNull()) {
            metadata = request.metadata();
            feeKind = validateMetadata(metadata, existing.type(), errors);
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new Merged(occurredAt, totalMinor, taxLines, feeLines, metadata, feeKind);
    }

    /**
     * Validates the type-specific metadata blob and returns the derived fee_kind
     * (navan_fee only). Shared by POST and PATCH; appends every problem found.
     */
    private FeeKind validateMetadata(JsonNode metadata, TransactionType type, List<FieldError> errors) {
        if (metadata == null || metadata.isNull()) {
            errors.add(new FieldError("metadata", "is required and must be a JSON object with type-specific fields"));
            return null;
        }
        if (!metadata.isObject()) {
            errors.add(new FieldError("metadata", "must be a JSON object"));
            return null;
        }
        checkMetadataSize(metadata, errors);
        return switch (type == null ? null : type) {
            case FLIGHT -> {
                FlightValidator.validate(metadata, errors);
                yield null;
            }
            case HOTEL -> {
                HotelValidator.validate(metadata, errors);
                yield null;
            }
            case RAIL -> {
                RailValidator.validate(metadata, errors);
                yield null;
            }
            case NAVAN_FEE -> NavanFeeValidator.validate(metadata, errors);
            case null, default -> null;
        };
    }

    private static TransactionType parseType(String type, List<FieldError> errors) {
        if (type == null || type.isBlank()) {
            errors.add(new FieldError("type", "is required"));
            return null;
        }
        TransactionType parsed = TransactionType.fromWire(type);
        if (parsed == null) {
            errors.add(new FieldError("type", "must be one of: flight, hotel, rail, navan_fee"));
        }
        return parsed;
    }

    private static String parseCurrency(String currency, List<FieldError> errors) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            errors.add(new FieldError("currency", "must be a 3-letter uppercase ISO-4217 code (e.g. EUR)"));
            return null;
        }
        try {
            Currency.getInstance(currency);
            return currency;
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError("currency", "is not a known ISO-4217 code"));
            return null;
        }
    }

    private static LocalDateTime parseOccurredAt(String occurredAt, List<FieldError> errors) {
        if (occurredAt == null || occurredAt.isBlank()) {
            errors.add(new FieldError("occurred_at", "is required"));
            return null;
        }
        try {
            return OffsetDateTime.parse(occurredAt)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException e) {
            errors.add(new FieldError("occurred_at",
                    "must be an ISO-8601 timestamp with an offset (e.g. 2026-03-11T06:40:00Z)"));
            return null;
        }
    }

    private static long parseAmount(BigDecimal amount, String field, List<FieldError> errors) {
        try {
            return Money.fromDecimal(amount, field).minor();
        } catch (ValidationException e) {
            errors.addAll(e.errors());
            return 0;
        }
    }

    private static List<TaxLine> parseTaxLines(List<TaxLineRequest> lines, List<FieldError> errors) {
        if (lines == null) {
            errors.add(new FieldError("tax_lines", "is required (use [] when there is no tax)"));
            return List.of();
        }
        if (lines.size() > MAX_TAX_LINES) {
            errors.add(new FieldError("tax_lines", "must have at most " + MAX_TAX_LINES + " lines"));
        }
        List<TaxLine> parsed = new ArrayList<>();
        for (int i = 0; i < Math.min(lines.size(), MAX_TAX_LINES); i++) {
            TaxLineRequest line = lines.get(i);
            String field = "tax_lines[" + i + "]";
            if (line == null) {
                errors.add(new FieldError(field, "must be a JSON object, not null"));
                continue;
            }
            String name = checkedName(line.name(), field + ".name", errors);
            long amountMinor = parseAmount(line.amount(), field + ".amount", errors);
            BigDecimal rate = null;
            if (line.rate() != null) {
                BigDecimal raw = line.rate();
                if (raw.signum() < 0 || raw.compareTo(BigDecimal.ONE) > 0) {
                    errors.add(new FieldError(field + ".rate", "must be between 0 and 1"));
                } else {
                    rate = roundedRate(raw);
                }
            }
            if (name != null && amountMinor >= 0) {
                parsed.add(new TaxLine(name, rate, amountMinor));
            }
        }
        return List.copyOf(parsed);
    }

    private static List<FeeLine> parseFeeLines(List<FeeLineRequest> lines, List<FieldError> errors) {
        if (lines == null) {
            errors.add(new FieldError("fee_lines", "is required (use [] when there are no fees)"));
            return List.of();
        }
        if (lines.size() > MAX_FEE_LINES) {
            errors.add(new FieldError("fee_lines", "must have at most " + MAX_FEE_LINES + " lines"));
        }
        List<FeeLine> parsed = new ArrayList<>();
        for (int i = 0; i < Math.min(lines.size(), MAX_FEE_LINES); i++) {
            FeeLineRequest line = lines.get(i);
            String field = "fee_lines[" + i + "]";
            if (line == null) {
                errors.add(new FieldError(field, "must be a JSON object, not null"));
                continue;
            }
            String name = checkedName(line.name(), field + ".name", errors);
            long amountMinor = parseAmount(line.amount(), field + ".amount", errors);
            if (name != null && amountMinor >= 0) {
                parsed.add(new FeeLine(name, amountMinor));
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * Rounds a rate to 4 decimal places. Rates are range-checked to [0,1] but not
     * scale-checked, and {@code setScale} on a hostile {@code 1E-999999999} tries
     * to build a 10^999999999 factor. When every significant digit sits more than
     * one place past the fourth decimal the value rounds to zero, so short-circuit.
     */
    private static BigDecimal roundedRate(BigDecimal rate) {
        if (rate.signum() == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        BigDecimal normalized = rate.stripTrailingZeros();
        if ((long) normalized.scale() - normalized.precision() > 4) {
            return BigDecimal.ZERO.setScale(4);
        }
        return normalized.setScale(4, RoundingMode.HALF_UP);
    }

    private static String checkedName(String name, String field, List<FieldError> errors) {
        if (name == null || name.isBlank()) {
            errors.add(new FieldError(field, "is required and must not be blank"));
            return null;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            errors.add(new FieldError(field, "must be at most " + MAX_NAME_LENGTH + " characters"));
            return null;
        }
        return name;
    }

    private void checkMetadataSize(JsonNode metadata, List<FieldError> errors) {
        try {
            int bytes = objectMapper.writeValueAsBytes(metadata).length;
            if (bytes > MAX_METADATA_BYTES) {
                errors.add(new FieldError("metadata", "must be at most 64 KB (was " + bytes + " bytes)"));
            }
        } catch (RuntimeException e) {
            errors.add(new FieldError("metadata", "could not be serialized"));
        }
    }
}
