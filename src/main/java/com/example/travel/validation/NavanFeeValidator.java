package com.example.travel.validation;

import java.util.List;

import com.example.travel.domain.FeeKind;
import com.example.travel.domain.ValidationException.FieldError;
import tools.jackson.databind.JsonNode;

/**
 * navan_fee metadata: fee_kind (trip_fee | agent_call_fee), optional
 * description and related_external_id (a soft reference — no foreign key,
 * the referenced booking may not exist yet).
 */
final class NavanFeeValidator {

    private NavanFeeValidator() {
    }

    static FeeKind validate(JsonNode metadata, List<FieldError> errors) {
        JsonNode node = metadata.get("fee_kind");
        if (node == null || node.isNull() || !node.isString() || node.asString().isBlank()) {
            errors.add(new FieldError("metadata.fee_kind", "is required (trip_fee or agent_call_fee)"));
            return null;
        }
        FeeKind feeKind = FeeKind.fromWire(node.asString());
        if (feeKind == null) {
            errors.add(new FieldError("metadata.fee_kind", "must be one of: trip_fee, agent_call_fee"));
        }
        MetadataRules.optionalText(metadata, "description", 500, errors);
        MetadataRules.optionalText(metadata, "related_external_id", 255, errors);
        return feeKind;
    }
}
