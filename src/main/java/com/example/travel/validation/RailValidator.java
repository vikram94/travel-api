package com.example.travel.validation;

import java.util.List;

import com.example.travel.domain.ValidationException.FieldError;
import tools.jackson.databind.JsonNode;

/**
 * Rail metadata: legs (a journey with a change is two legs, not one OD pair).
 * At least one leg; arrival must not precede departure.
 */
final class RailValidator {

    private RailValidator() {
    }

    static void validate(JsonNode metadata, List<FieldError> errors) {
        JsonNode legs = MetadataRules.array(metadata, "legs", "metadata.legs", errors);
        if (legs == null) {
            return;
        }
        if (legs.isEmpty()) {
            errors.add(new FieldError("metadata.legs", "must have at least one leg"));
            return;
        }
        for (int i = 0; i < legs.size(); i++) {
            JsonNode leg = legs.get(i);
            String field = "metadata.legs[" + i + "]";
            if (leg == null || !leg.isObject()) {
                errors.add(new FieldError(field, "must be an object"));
                continue;
            }
            MetadataRules.text(leg, "origin", field + ".origin", 200, errors);
            MetadataRules.text(leg, "destination", field + ".destination", 200, errors);
            MetadataRules.optionalText(leg, "train_number", 100, errors);
            MetadataRules.optionalText(leg, "class", 100, errors);
            var departure = MetadataRules.instant(leg, "departure", field + ".departure", errors);
            var arrival = MetadataRules.instant(leg, "arrival", field + ".arrival", errors);
            if (departure != null && arrival != null && arrival.isBefore(departure)) {
                errors.add(new FieldError(field + ".arrival", "must not be before departure"));
            }
        }
    }
}
