package com.example.travel.validation;

import java.util.List;

import com.example.travel.domain.ValidationException.FieldError;
import tools.jackson.databind.JsonNode;

/**
 * Flight metadata: origin, destination, fare, coupons (segments).
 * Empty coupons are allowed (a bare fare); arrival must not precede departure.
 */
final class FlightValidator {

    private FlightValidator() {
    }

    static void validate(JsonNode metadata, List<FieldError> errors) {
        MetadataRules.text(metadata, "origin", "metadata.origin", 100, errors);
        MetadataRules.text(metadata, "destination", "metadata.destination", 100, errors);
        MetadataRules.amount(metadata, "fare", "metadata.fare", errors);

        JsonNode coupons = MetadataRules.array(metadata, "coupons", "metadata.coupons", errors);
        if (coupons == null) {
            return;
        }
        for (int i = 0; i < coupons.size(); i++) {
            JsonNode coupon = coupons.get(i);
            String field = "metadata.coupons[" + i + "]";
            if (coupon == null || !coupon.isObject()) {
                errors.add(new FieldError(field, "must be an object"));
                continue;
            }
            MetadataRules.text(coupon, "flight_number", field + ".flight_number", 100, errors);
            MetadataRules.text(coupon, "from", field + ".from", 100, errors);
            MetadataRules.text(coupon, "to", field + ".to", 100, errors);
            MetadataRules.optionalText(coupon, "carrier", 100, errors);
            var departure = MetadataRules.instant(coupon, "departure", field + ".departure", errors);
            var arrival = MetadataRules.instant(coupon, "arrival", field + ".arrival", errors);
            if (departure != null && arrival != null && arrival.isBefore(departure)) {
                errors.add(new FieldError(field + ".arrival", "must not be before departure"));
            }
        }
    }
}
