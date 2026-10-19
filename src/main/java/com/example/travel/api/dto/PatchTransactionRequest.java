package com.example.travel.api.dto;

import java.math.BigDecimal;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * PATCH /transactions/{id} body. Every field is optional: an absent field keeps
 * the stored value. Because none of the patchable fields accept null, a Java
 * null is always "absent" — except that {@code tax_lines: []} (present, empty)
 * clears the list while an absent/missing {@code tax_lines} leaves it alone.
 *
 * The immutable fields cannot tell "absent" from "null" either (a record cannot),
 * but that does not matter: they are only read to reject a request that tries to
 * change type / external_id / currency (400) — sending the same value is a no-op.
 */
public record PatchTransactionRequest(
        String type,
        String externalId,
        String currency,
        String occurredAt,
        BigDecimal total,
        List<TaxLineRequest> taxLines,
        List<FeeLineRequest> feeLines,
        JsonNode metadata) {
}
